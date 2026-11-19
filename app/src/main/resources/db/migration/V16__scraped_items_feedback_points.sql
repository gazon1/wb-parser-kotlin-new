-- The WB points a product pays for a review — «Баллы ВБ за отзыв».
--
-- WB sends them in the product object as `feedbackPoints` and renders them as "N Б ЗА
-- ОТЗЫВ". Until now the number had no column of its own: it died in the DTO, so the
-- catalog could neither show nor filter by it, and the crawler's own filter
-- (`ffeedbackpoints=1`) narrowed the answer to products whose points were then thrown
-- away.
--
-- A separate column from `cashback_percent` on purpose: the points are a count WB pays
-- for a review, the wallet cashback is a percentage of the price. A product with 300
-- points usually carries a wallet cashback of zero.
ALTER TABLE scraped_items
    ADD COLUMN IF NOT EXISTS feedback_points INTEGER;
