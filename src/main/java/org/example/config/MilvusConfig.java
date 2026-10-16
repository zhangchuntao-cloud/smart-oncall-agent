package org.example.config;

import io.milvus.client.MilvusServiceClient;
import org.example.client.MilvusClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import jakarta.annotation.PreDestroy;

/**
 * Milvus 配置类
 * 负责创建和管理 MilvusServiceClient Bean
 *
 * <p><b>为什么要 {@link Lazy}</b>：{@link MilvusClientFactory#createClient()} 在建立连接后
 * 还会检查并创建 collection 与索引，任何一步失败都会抛异常。原实现是 eager 初始化，
 * 于是<b>Milvus 没起来 = 整个应用起不来</b>——向量检索只是 RAG 的一个可选能力，
 * 不该有这种否决权。
 *
 * <p>改成懒加载后：应用照常启动；首次真正用到向量检索时才建立连接，失败则由
 * 调用方（{@code InternalDocsTools} 的 try/catch）转成一条明确的错误信息交给模型，
 * 而不是让整个服务不可用。注入点也需要标 {@code @Lazy}，否则 eager 的注入方
 * 仍会在启动时触发创建——所以 {@code VectorSearchService}、{@code VectorIndexService}、
 * {@code MilvusCheckController} 三处的字段都加了 {@code @Lazy}。
 */
@Configuration
public class MilvusConfig {

    private static final Logger logger = LoggerFactory.getLogger(MilvusConfig.class);

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    private MilvusServiceClient milvusClient;

    /**
     * 创建 MilvusServiceClient Bean（懒加载：首次使用时才连接）
     *
     * @return MilvusServiceClient 实例
     */
    @Bean
    @Lazy
    public MilvusServiceClient milvusServiceClient() {
        logger.info("正在初始化 Milvus 客户端...");
        milvusClient = milvusClientFactory.createClient();
        logger.info("Milvus 客户端初始化完成");
        return milvusClient;
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            logger.info("正在关闭 Milvus 客户端连接...");
            milvusClient.close();
            logger.info("Milvus 客户端连接已关闭");
        }
    }
}
