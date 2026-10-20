-- WB's product-subject dictionary: id → name.
--
-- The WB catalog API sends each product's place in the category tree as ids
-- only (`subjectId`, `subjectParentId`). The names live in a static dictionary
-- that WB publishes separately, so every scraped row used to carry an id with
-- no name next to it, and `scraped_items.catalog_name` was NULL for 100% of
-- rows.
--
-- The dictionary is loaded by `scripts/wb-catalog/load_subjects.py`
-- (`just catalog::load-subjects`). It is a reference table: the crawler only
-- reads it, and an empty table means "no names yet", not "no names exist".
CREATE TABLE IF NOT EXISTS wb_subjects (
    id   BIGINT PRIMARY KEY,
    name TEXT NOT NULL
);
