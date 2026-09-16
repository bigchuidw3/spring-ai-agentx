package com.agentx.ai.samples.rag;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * Redis 向量索引诊断：列出索引、查看 schema 字段、统计 key、验证检索。
 *
 * @author bigchui
 */
public class RedisIndexCleanup {

    private static final String REDIS_HOST = "192.168.113.52";
    private static final int REDIS_PORT = 6399;
    private static final String REDIS_PASSWORD = "8uhb*UHB";
    private static final String INDEX_NAME = "agentx-rag-vector";
    private static final String DOC_TYPE = "docType";

    public static void main(String[] args) {
        JedisPooled jedis = new JedisPooled(
                new HostAndPort(REDIS_HOST, REDIS_PORT),
                DefaultJedisClientConfig.builder()
                        .password(REDIS_PASSWORD)
                        .connectionTimeoutMillis(10000)
                        .socketTimeoutMillis(10000)
                        .build());

        System.out.println("========== 1. 索引列表 ==========");
        System.out.println("FT._LIST -> " + jedis.ftList());

        System.out.println("\n========== 2. 索引 schema 字段（看有没有 docType）==========");
        try {
            java.util.Map<String, Object> info = jedis.ftInfo(INDEX_NAME);
            Object attrs = info.get("attributes");
            System.out.println("attributes = " + attrs);
            Object numDocs = info.get("num_docs");
            System.out.println("num_docs = " + numDocs);
        } catch (Exception e) {
            System.out.println("FT.INFO 失败: " + e.getMessage());
        }

        System.out.println("\n========== 3. key 数量 ==========");
        System.out.println("向量 key (rag:*) : " + countKeys(jedis, "rag:*"));
        System.out.println("父块 key : " + countKeys(jedis, "agentx:rag:document:*"));

        System.out.println("\n========== 4. 第一个向量 key 的 JSON（看 docType 有没有存进去）==========");
        redis.clients.jedis.params.ScanParams scanParams =
                new redis.clients.jedis.params.ScanParams().match("rag:*").count(5);
        redis.clients.jedis.resps.ScanResult<String> scan =
                jedis.scan(redis.clients.jedis.params.ScanParams.SCAN_POINTER_START, scanParams);
        if (!scan.getResult().isEmpty()) {
            String firstKey = scan.getResult().get(0);
            Object json = jedis.jsonGet(firstKey);
            String text = String.valueOf(json);
            System.out.println("key = " + firstKey);
            System.out.println("含 docType: " + text.contains(DOC_TYPE));
            System.out.println("含 aicube: " + text.contains("aicube"));
            System.out.println("含 fileName: " + text.contains("fileName"));
        } else {
            System.out.println("没有找到 rag:* 的 key，数据可能没写入");
        }

        System.exit(0);
    }

    private static long countKeys(JedisPooled jedis, String pattern) {
        long count = 0;
        String cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
        redis.clients.jedis.params.ScanParams params =
                new redis.clients.jedis.params.ScanParams().match(pattern).count(200);
        do {
            redis.clients.jedis.resps.ScanResult<String> result = jedis.scan(cursor, params);
            cursor = result.getCursor();
            count += result.getResult().size();
        } while (!redis.clients.jedis.params.ScanParams.SCAN_POINTER_START.equals(cursor));
        return count;
    }
}
