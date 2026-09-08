-- V1__init.sql: Initial schema for wb-parser
-- Replaces EF Core migrations from the C# solution

-- crawl_targets: the websites / categories we crawl
CREATE TABLE IF NOT EXISTS crawl_targets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    start_url       TEXT NOT NULL,
    allowed_domains TEXT[],
    max_depth       INT NOT NULL DEFAULT 1,
    parsing_rules   JSONB,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    known_pages_limit INT,
    wb_catalog_id   VARCHAR(100),
    wb_parent_catalog_id VARCHAR(100),
    root_category_name VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crawl_targets_is_active ON crawl_targets(is_active);
CREATE INDEX idx_crawl_targets_wb_catalog_id ON crawl_targets(wb_catalog_id);

-- crawl_jobs: one run of a spider
CREATE TABLE IF NOT EXISTS crawl_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id       UUID NOT NULL REFERENCES crawl_targets(id) ON DELETE CASCADE,
    status          VARCHAR(50) NOT NULL DEFAULT 'Created',
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    requests_total  INT NOT NULL DEFAULT 0,
    requests_succeeded INT NOT NULL DEFAULT 0,
    requests_failed INT NOT NULL DEFAULT 0,
    pages_crawled   INT NOT NULL DEFAULT 0,
    items_saved     INT NOT NULL DEFAULT 0,
    triggered_by    VARCHAR(100),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_status CHECK (status IN ('Created','Running','Completed','Failed','Cancelled','Crashed'))
);

CREATE INDEX idx_crawl_jobs_target_id ON crawl_jobs(target_id);
CREATE INDEX idx_crawl_jobs_status ON crawl_jobs(status);
CREATE INDEX idx_crawl_jobs_started_at ON crawl_jobs(started_at);

-- scraped_items: the actual scraped product data
CREATE TABLE IF NOT EXISTS scraped_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id       UUID NOT NULL REFERENCES crawl_targets(id) ON DELETE CASCADE,
    catalog_url     TEXT,
    product_url     TEXT NOT NULL,
    brand           VARCHAR(255),
    seller          VARCHAR(255),
    price_kopecks   BIGINT,
    title           TEXT,
    product_id      BIGINT,
    cashback        DECIMAL(10,2),
    cashback_percent DECIMAL(5,2),
    data            JSONB,
    content_hash    VARCHAR(64) NOT NULL,
    scraped_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    subject_id      BIGINT,
    subject_parent_id BIGINT,
    match_id        BIGINT,
    supplier_id     BIGINT,
    catalog_name    VARCHAR(255),
    CONSTRAINT uq_scraped_items_content_hash UNIQUE (content_hash)
);

CREATE INDEX idx_scraped_items_target_id ON scraped_items(target_id);
CREATE INDEX idx_scraped_items_product_id ON scraped_items(product_id);
CREATE INDEX idx_scraped_items_catalog_url ON scraped_items(catalog_url);
CREATE INDEX idx_scraped_items_content_hash ON scraped_items(content_hash);
CREATE INDEX idx_scraped_items_scraped_at ON scraped_items(scraped_at);

-- crawl_errors: errors encountered during crawling
CREATE TABLE IF NOT EXISTS crawl_errors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID REFERENCES crawl_jobs(id) ON DELETE SET NULL,
    target_id       UUID REFERENCES crawl_targets(id) ON DELETE CASCADE,
    url             TEXT,
    error_message   TEXT NOT NULL,
    stack_trace     TEXT,
    category        VARCHAR(50),
    is_resolved     BOOLEAN NOT NULL DEFAULT false,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crawl_errors_job_id ON crawl_errors(job_id);
CREATE INDEX idx_crawl_errors_target_id ON crawl_errors(target_id);
CREATE INDEX idx_crawl_errors_is_resolved ON crawl_errors(is_resolved);

-- target_api_contexts: WB API auth headers per target
CREATE TABLE IF NOT EXISTS target_api_contexts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id       UUID NOT NULL REFERENCES crawl_targets(id) ON DELETE CASCADE,
    base_api_url    TEXT,
    query_params    JSONB,
    headers         JSONB,
    cookie_header   TEXT,
    max_pages       INT NOT NULL DEFAULT 10,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_target_api_context_target UNIQUE (target_id)
);

-- category_mappings: product categories from the site
CREATE TABLE IF NOT EXISTS category_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id       UUID NOT NULL REFERENCES crawl_targets(id) ON DELETE CASCADE,
    catalog_id      VARCHAR(100),
    catalog_name    VARCHAR(255),
    node_name       VARCHAR(255),
    parent_node_id  VARCHAR(100),
    level           INT NOT NULL DEFAULT 0,
    UNIQUE(target_id, catalog_id)
);

CREATE INDEX idx_category_mappings_target_id ON category_mappings(target_id);
CREATE INDEX idx_category_mappings_catalog_id ON category_mappings(catalog_id);
