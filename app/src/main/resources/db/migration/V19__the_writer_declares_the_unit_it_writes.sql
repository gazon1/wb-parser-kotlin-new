-- The database refuses a write that does not say which unit it writes.
--
-- ## What this migration is for
--
-- `V17` changed what the cashback columns mean: `cashback_percent` holds the percentage
-- WB sends, and `cashback` holds the money that follows it. A writer that does not know
-- that writes the old unit into the new columns — the old writer wrote 0.03 ₽ for a
-- product with a 3 % cashback, and the new column reads that as 0.03 %.
--
-- Until now nothing stopped that writer. The runbook said the old crawler must not run
-- after `V17`, but a rule that lives in a document is a rule an operator has to remember,
-- and the failure it prevents is silent: the rows are written, the crawl succeeds, and the
-- catalog answers with cashbacks that are wrong by a factor of a hundred.
--
-- ## How the writer is stopped
--
-- The writer declares the unit in the session it writes in:
--
--     SET LOCAL crawler.cashback_unit = 'percent';
--
-- and the trigger below refuses every row written by a session that has not made that
-- declaration. The old release does not make it — the declaration is part of the write
-- path of the new release — so its writes fail, at the first row, with an error that
-- names the unit.
--
-- ## Why the declaration is a session setting and not a column
--
-- A column says what a row holds; it does not say what the writer meant. The writer that
-- has to be stopped is the one that never heard of the column: its INSERT does not
-- mention it, so a column with a default takes the default, and a column without one
-- refuses every write — the new one's included. The session setting is the one place
-- where the writer of the old unit is caught: it does not know about it either.
--
-- ## Why the trigger does not look at the values
--
-- It could ask whether the numbers look like a percentage — an amount below a ruble,
-- say. It does not, because that question has no right answer: a product that costs 10 ₽
-- with a 1 % cashback is 0.10 ₽ of money, and a guard that refused it would refuse a
-- correct row. The declaration is what the writer says, and the writer is the only one
-- that knows.
--
-- ## What this does not protect
--
-- A writer that declares the unit and then writes the other one is not caught. That is a
-- bug in the writer, not a mismatch of releases: the declaration is a promise, and this
-- migration checks that the promise is made.
--
-- ## Why the trigger is on UPDATE as well
--
-- The writer upserts: a row that is already there is updated, not inserted. A trigger on
-- INSERT alone would let the old writer change the rows it has already written — which
-- is the same corruption, one row at a time.

DROP TRIGGER IF EXISTS trg_scraped_items_require_the_declared_cashback_unit ON scraped_items;

CREATE OR REPLACE FUNCTION wbparser_require_the_declared_cashback_unit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('crawler.cashback_unit', true) IS DISTINCT FROM 'percent' THEN
        RAISE EXCEPTION
            'scraped_items holds the cashback as a percentage: the writer must declare the unit '
            'it writes with SET LOCAL crawler.cashback_unit = ''percent'''
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_scraped_items_require_the_declared_cashback_unit
    BEFORE INSERT OR UPDATE ON scraped_items
    FOR EACH ROW
    EXECUTE FUNCTION wbparser_require_the_declared_cashback_unit();
