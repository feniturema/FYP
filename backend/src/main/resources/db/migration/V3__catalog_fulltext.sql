-- P5a: FULLTEXT search over products and second-hand items (docs/phases/P5a.md §6.1).
-- ngram parser (default ngram_token_size = 2) so Chinese and Malay text is tokenised; terms shorter than
-- 2 characters never match. Index-only change: existing rows are read, not modified.
ALTER TABLE products ADD FULLTEXT INDEX ft_products (name, description, category) WITH PARSER ngram;
ALTER TABLE items    ADD FULLTEXT INDEX ft_items (title, description, category) WITH PARSER ngram;
