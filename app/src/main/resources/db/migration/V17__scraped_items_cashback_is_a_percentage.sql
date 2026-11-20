-- The meaning of the cashback columns changed: `sizes[0].price.cashback` is a
-- percentage (1..75), not an amount of money.
--
-- What the old writer did with the field is what this migration has to undo.
-- It took the percentage for kopecks and wrote it into `cashback` as rubles:
-- a product with a 3 % cashback was stored as 0.03 rubles. It derived
-- `cashback_percent` from that amount and the price, so the stored percentage
-- was a number that depended on the price rather than on the cashback — for a
-- 2590 ₽ product it was 0.00 % (the column holds two decimals), for a
-- 1000 ₽ product it was 0.30 %.
--
-- So the percentage is the stored amount times a hundred — the amount was the
-- percentage divided by a hundred — and the money amount follows the price
-- actually paid, the discounted one when there is one. The old writer stored
-- the amount even when it had no price to derive a percentage from, so the
-- percentage is restored for every observation that carries one, and the money
-- amount is left empty where the price that would give it is missing.
-- Rows without a cashback keep their NULL: WB sends no cashback unless the
-- request asks for it.
--
-- The percentage is a measured fact: the live catalog answered `cashback` 1..3 for
-- products priced 850..10000 ₽, and read as an amount in kopecks those numbers
-- would be 0.01..0.03 ₽ — a value no storefront shows. The measurement is committed
-- as evidence under
-- devtools/wb-research/investigations/2026-11-19-feedback-points-filter/evidence/.
--
-- The amount is written in rubles, like the old writer wrote it. The price is in
-- kopecks, so a percentage of the price is in kopecks too, and a ruble is a
-- hundred kopecks: 2590 ₽ at 3 % is 7770 kopecks, which is 77.70 ₽. A price
-- that gives a fraction of a kopeck is truncated, the way the writer that writes
-- the new rows truncates it — a migrated row and a scraped row of the same
-- product must carry the same amount.
WITH restored AS (
    SELECT id,
           cashback * 100 AS percent,
           COALESCE(sale_price_kopecks, price_kopecks) AS paid_kopecks
    FROM scraped_items
    WHERE cashback IS NOT NULL
      AND cashback > 0
)
UPDATE scraped_items AS item
SET cashback_percent = restored.percent,
    cashback =
        CASE
            WHEN restored.paid_kopecks > 0
            THEN TRUNC(restored.paid_kopecks * restored.percent / 10000.0, 2)
            ELSE NULL
        END
FROM restored
WHERE item.id = restored.id;
