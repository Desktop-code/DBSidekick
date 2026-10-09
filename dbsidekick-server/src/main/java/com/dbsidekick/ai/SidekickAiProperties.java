package com.dbsidekick.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 相关配置：Embedding + Milvus + LLM。
 */
@ConfigurationProperties(prefix = "sidekick.ai")
public class SidekickAiProperties {

    private final Embedding embedding = new Embedding();
    private final Milvus milvus = new Milvus();
    private final Llm llm = new Llm();
    private final Rerank rerank = new Rerank();
    private final Fewshot fewshot = new Fewshot();

    public Embedding getEmbedding() {
        return embedding;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public Llm getLlm() {
        return llm;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Fewshot getFewshot() {
        return fewshot;
    }

    public static class Embedding {
        /** onnx | ollama */
        private String provider = "onnx";
        /** ONNX 模型目录（含 model.onnx、tokenizer.json），由用户在首次启动时选择 */
        private String onnxModelPath = "";
        private String ollamaUrl = "http://localhost:11434";
        private String ollamaModel = "bge-base-zh-1.5b";
        private int dimension = 768;
        private String onnxFile = "model.onnx";
        private String tokenizerFile = "tokenizer.json";
        private String pooling = "CLS";
        private String queryPrefix = "为这个句子生成表示以用于检索相关文章：";
        private int maxSequenceLength = 512;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getOnnxModelPath() {
            return onnxModelPath;
        }

        public void setOnnxModelPath(String onnxModelPath) {
            this.onnxModelPath = onnxModelPath;
        }

        public String getOllamaUrl() {
            return ollamaUrl;
        }

        public void setOllamaUrl(String ollamaUrl) {
            this.ollamaUrl = ollamaUrl;
        }

        public String getOllamaModel() {
            return ollamaModel;
        }

        public void setOllamaModel(String ollamaModel) {
            this.ollamaModel = ollamaModel;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getOnnxFile() {
            return onnxFile;
        }

        public void setOnnxFile(String onnxFile) {
            this.onnxFile = onnxFile;
        }

        public String getTokenizerFile() {
            return tokenizerFile;
        }

        public void setTokenizerFile(String tokenizerFile) {
            this.tokenizerFile = tokenizerFile;
        }

        public String getPooling() {
            return pooling;
        }

        public void setPooling(String pooling) {
            this.pooling = pooling;
        }

        public String getQueryPrefix() {
            return queryPrefix;
        }

        public void setQueryPrefix(String queryPrefix) {
            this.queryPrefix = queryPrefix;
        }

        public int getMaxSequenceLength() {
            return maxSequenceLength;
        }

        public void setMaxSequenceLength(int maxSequenceLength) {
            this.maxSequenceLength = maxSequenceLength;
        }
    }

    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String collection = "schema_chunks";
        private int dimension = 768;
        private int connectTimeoutMs = 8000;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }
    }

    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        /** 仅从环境变量 DEEPSEEK_API_KEY 注入，勿写死 */
        private String apiKey = "";
        private String model = "deepseek-v4-pro";
        private int timeoutSeconds = 300;
        private double temperature = 0.1;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public static class Rerank {
        private boolean enabled = true;
        private int candidateTopK = 10;
        private int finalTopK = 5;
        private int timeoutSeconds = 3;
        private long cacheTtlMs = 60_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCandidateTopK() {
            return candidateTopK;
        }

        public void setCandidateTopK(int candidateTopK) {
            this.candidateTopK = candidateTopK;
        }

        public int getFinalTopK() {
            return finalTopK;
        }

        public void setFinalTopK(int finalTopK) {
            this.finalTopK = finalTopK;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public long getCacheTtlMs() {
            return cacheTtlMs;
        }

        public void setCacheTtlMs(long cacheTtlMs) {
            this.cacheTtlMs = cacheTtlMs;
        }
    }

    public static class Fewshot {
        private boolean enabled = true;
        private int topK = 3;
        private int minRowCount = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getMinRowCount() {
            return minRowCount;
        }

        public void setMinRowCount(int minRowCount) {
            this.minRowCount = minRowCount;
        }
    }
}
