package com.agentx.ai.rag.parser.mineru;

import com.agentx.ai.rag.asset.DocumentAsset;
import com.agentx.ai.rag.asset.DocumentAssetStore;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.ImageReferences;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.parser.ParsedBlock;
import com.agentx.ai.rag.parser.ParsedDocument;
import com.agentx.ai.rag.parser.image.ImageDescription;
import com.agentx.ai.rag.parser.image.ImageDescriptionRequest;
import com.agentx.ai.rag.parser.image.ImageDescriber;
import com.agentx.ai.rag.parser.image.ImageMediaTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * MinerU 图片后处理器。
 *
 * <p>负责把图片字节保存为长期资产、生成图片语义描述，并把两者写回 Markdown
 * 图片引用和 image block metadata。该类不负责 MinerU API 调用和文本分块。
 *
 * @author bigchui
 */
final class MineruImagePostProcessor {

    private final DocumentAssetStore assetStore;
    private final ImageDescriber imageDescriber;
    private final Semaphore describePermits;

    MineruImagePostProcessor(DocumentAssetStore assetStore,
                             ImageDescriber imageDescriber,
                             int imageWorkers) {
        this.assetStore = assetStore;
        this.imageDescriber = imageDescriber;
        this.describePermits = new Semaphore(imageWorkers);
    }

    ParsedDocument process(String assetScope, ParsedDocument document) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(assetScope, "assetScope");

        Map<String, ProcessedImage> images = new LinkedHashMap<>();
        List<ParsedBlock> blocks = new ArrayList<>(document.blocks().size());
        Map<Integer, CompletableFuture<ProcessedImage>> tasks = new LinkedHashMap<>();
        Map<Integer, ProcessedImage> processedImages = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < document.blocks().size(); index++) {
                ParsedBlock block = document.blocks().get(index);
                if (block.type() != ContentType.IMAGE) {
                    blocks.add(block);
                    continue;
                }

                PendingImage pendingImage = prepareImage(assetScope, document, block);
                if (shouldDescribe(pendingImage)) {
                    blocks.add(null);
                    tasks.put(index, CompletableFuture.supplyAsync(
                            () -> describeImage(pendingImage), executor));
                } else {
                    ProcessedImage processedImage = completeImage(pendingImage, null);
                    blocks.add(processedImage.block());
                    processedImages.put(index, processedImage);
                }
            }
            awaitImages(tasks);
            tasks.forEach((index, task) -> {
                ProcessedImage processedImage = task.join();
                blocks.set(index, processedImage.block());
                processedImages.put(index, processedImage);
            });
        }

        for (ProcessedImage image : processedImages.values()) {
            if (!image.sourcePath().isBlank()) {
                images.put(ImageReferences.normalizeUri(image.sourcePath()), image);
            }
        }

        List<ParsedBlock> rewrittenBlocks = rewriteMarkdownImages(blocks, images);
        return new ParsedDocument(
                document.fileName(),
                rewrittenBlocks,
                documentMetadata(document, rewrittenBlocks)
        );
    }

    private PendingImage prepareImage(String assetScope, ParsedDocument document, ParsedBlock block) {
        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
        String sourcePath = stringValue(metadata.get("sourceImagePath"));
        String mediaType = stringValue(metadata.get("mediaType"));
        if (mediaType.isBlank()) {
            mediaType = ImageMediaTypes.fromFileName(sourcePath);
            metadata.put("mediaType", mediaType);
        }

        DocumentAsset asset = null;
        byte[] binary = block.binary();
        if (assetStore != null && binary != null && binary.length > 0 && !sourcePath.isBlank()) {
            asset = assetStore.save(assetScope, sourcePath, binary, mediaType);
            metadata.put("assetId", asset.assetId());
            metadata.put("assetUri", asset.uri());
            metadata.put("assetMediaType", asset.mediaType());
            metadata.put("assetSize", asset.size());
        }

        return new PendingImage(
                document.fileName(),
                sourcePath,
                block.text(),
                mediaType,
                binary,
                asset,
                new ParsedBlock(ContentType.IMAGE, block.text(), block.binary(), metadata)
        );
    }

    private boolean shouldDescribe(PendingImage image) {
        return imageDescriber != null
                && image.binary() != null
                && image.binary().length > 0
                && !image.sourcePath().isBlank();
    }

    private ProcessedImage describeImage(PendingImage image) {
        acquirePermit();
        try {
            ImageDescription description = imageDescriber.describe(new ImageDescriptionRequest(
                    image.documentFileName(),
                    image.sourcePath(),
                    image.caption(),
                    image.mediaType(),
                    image.binary()
            ));
            return completeImage(image, description);
        } finally {
            describePermits.release();
        }
    }

    private ProcessedImage completeImage(PendingImage image, ImageDescription description) {
        Map<String, Object> metadata = new LinkedHashMap<>(image.block().metadata());
        if (description != null) {
            metadata.put("imageDescription", description.text());
            metadata.put("imageDescriptionModel", description.modelName());
            metadata.put("hasImageDescription", true);
        }

        String text = description == null ? image.caption() : description.text();
        return new ProcessedImage(
                image.sourcePath(),
                new ParsedBlock(ContentType.IMAGE, text, null, metadata),
                image.asset(),
                description
        );
    }

    private void awaitImages(Map<Integer, CompletableFuture<ProcessedImage>> tasks) {
        try {
            CompletableFuture.allOf(tasks.values().toArray(new CompletableFuture[0]))
                    .join();
        } catch (CompletionException e) {
            throw imageExecutionFailure(e.getCause() == null ? e : e.getCause());
        }
    }

    private RuntimeException imageExecutionFailure(Throwable cause) {
        if (cause instanceof RagException ragException) {
            return ragException;
        }
        return new RagException(RagErrorCode.IMAGE_UNDERSTANDING_FAILED,
                "图片语义理解并发执行失败", cause);
    }

    private void acquirePermit() {
        try {
            describePermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException(RagErrorCode.IMAGE_UNDERSTANDING_FAILED,
                    "图片语义理解线程被中断", e);
        }
    }

    private List<ParsedBlock> rewriteMarkdownImages(List<ParsedBlock> blocks,
                                                     Map<String, ProcessedImage> images) {
        if (images.isEmpty()) {
            return blocks;
        }

        List<ParsedBlock> result = new ArrayList<>(blocks.size());
        for (ParsedBlock block : blocks) {
            if (block.type() != ContentType.TEXT) {
                result.add(block);
                continue;
            }

            String markdown = ImageReferences.replace(block.text(), reference -> {
                ProcessedImage image = images.get(ImageReferences.normalizeUri(reference.uri()));
                return image == null ? reference.token() : markdownReference(reference, image);
            });
            result.add(new ParsedBlock(ContentType.TEXT, markdown, block.binary(), block.metadata()));
        }
        return result;
    }

    private String markdownReference(ImageReferences.ImageReference reference,
                                      ProcessedImage image) {
        String uri = image.asset() == null ? reference.uri() : image.asset().uri();
        String alt = image.description() == null
                ? firstNonBlank(reference.alt(), image.block().text())
                : image.description().text();
        return "![" + escapeAlt(alt) + "](" + formatUri(uri) + ")";
    }

    private Map<String, Object> documentMetadata(ParsedDocument document, List<ParsedBlock> blocks) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
        long imageCount = blocks.stream().filter(block -> block.type() == ContentType.IMAGE).count();
        metadata.put("imageCount", imageCount);
        metadata.put("imageReferenceCount", countImageReferences(blocks));
        metadata.put("imageUnderstandingEnabled", imageDescriber != null);
        metadata.put("assetStorageEnabled", assetStore != null);
        return metadata;
    }

    private int countImageReferences(List<ParsedBlock> blocks) {
        return blocks.stream()
                .mapToInt(block -> ImageReferences.find(block.text()).size())
                .sum();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String escapeAlt(String value) {
        return value.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replaceAll("[\\r\\n]+", " ");
    }

    private String formatUri(String uri) {
        return uri.matches("\\S+") ? uri : "<" + uri.replace(">", "%3E") + ">";
    }

    private record ProcessedImage(
            String sourcePath,
            ParsedBlock block,
            DocumentAsset asset,
            ImageDescription description
    ) {
    }

    private record PendingImage(
            String documentFileName,
            String sourcePath,
            String caption,
            String mediaType,
            byte[] binary,
            DocumentAsset asset,
            ParsedBlock block
    ) {
    }
}
