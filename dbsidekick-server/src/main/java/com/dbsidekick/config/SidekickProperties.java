package com.dbsidekick.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sidekick")
public class SidekickProperties {

    private final Milvus milvus = new Milvus();
    private final Embedding embedding = new Embedding();
    private final Relation relation = new Relation();

    public Milvus getMilvus() {
        return milvus;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Relation getRelation() {
        return relation;
    }

    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String collection = "schema_chunks";

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
    }

    public static class Embedding {
        private String model = "bge-base-zh-1.5b";
        private int dimension = 768;
        private String ollamaUrl = "http://localhost:11434";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getOllamaUrl() {
            return ollamaUrl;
        }

        public void setOllamaUrl(String ollamaUrl) {
            this.ollamaUrl = ollamaUrl;
        }
    }

    /**
     * yml 层（sidekick.relation.*）。未配置的项保持 null，由 RelationConfigService 落到代码兜底。
     */
    public static class Relation {
        private Integer confidenceThreshold;
        private String excludedColumnNames;
        private String tempTablePrefixes;
        private Integer adjacencyWhitelistLimit;
        private Integer inferredThresholdMultiplier;
        private final Score score = new Score();

        public Integer getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public void setConfidenceThreshold(Integer confidenceThreshold) {
            this.confidenceThreshold = confidenceThreshold;
        }

        public String getExcludedColumnNames() {
            return excludedColumnNames;
        }

        public void setExcludedColumnNames(String excludedColumnNames) {
            this.excludedColumnNames = excludedColumnNames;
        }

        public String getTempTablePrefixes() {
            return tempTablePrefixes;
        }

        public void setTempTablePrefixes(String tempTablePrefixes) {
            this.tempTablePrefixes = tempTablePrefixes;
        }

        public Integer getAdjacencyWhitelistLimit() {
            return adjacencyWhitelistLimit;
        }

        public void setAdjacencyWhitelistLimit(Integer adjacencyWhitelistLimit) {
            this.adjacencyWhitelistLimit = adjacencyWhitelistLimit;
        }

        public Integer getInferredThresholdMultiplier() {
            return inferredThresholdMultiplier;
        }

        public void setInferredThresholdMultiplier(Integer inferredThresholdMultiplier) {
            this.inferredThresholdMultiplier = inferredThresholdMultiplier;
        }

        public Score getScore() {
            return score;
        }
    }

    public static class Score {
        private Integer sqlSuccess;
        private Integer rowCountPositive;
        private Integer savedToScript;
        private Integer userConfirmed;
        private Integer userRejected;

        public Integer getSqlSuccess() {
            return sqlSuccess;
        }

        public void setSqlSuccess(Integer sqlSuccess) {
            this.sqlSuccess = sqlSuccess;
        }

        public Integer getRowCountPositive() {
            return rowCountPositive;
        }

        public void setRowCountPositive(Integer rowCountPositive) {
            this.rowCountPositive = rowCountPositive;
        }

        public Integer getSavedToScript() {
            return savedToScript;
        }

        public void setSavedToScript(Integer savedToScript) {
            this.savedToScript = savedToScript;
        }

        public Integer getUserConfirmed() {
            return userConfirmed;
        }

        public void setUserConfirmed(Integer userConfirmed) {
            this.userConfirmed = userConfirmed;
        }

        public Integer getUserRejected() {
            return userRejected;
        }

        public void setUserRejected(Integer userRejected) {
            this.userRejected = userRejected;
        }
    }
}
