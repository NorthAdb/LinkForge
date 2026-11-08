-- Batch 3a (P1): backend link search/tags + dynamic routing rules.
-- tags: normalized comma-joined string on the link row (lowercase, deduped,
-- single tag [a-z0-9_-]{1,32}, at most 10). One column on purpose at this
-- scale — no join table; whole-word lookup goes through FIND_IN_SET.
-- match_rule: optional JSON text on a variant (devices/os/languages/
-- timeWindows). NULL = wildcard (participates in every draw). The redirect
-- evaluates it after all gates pass, narrowing the weighted draw; MyBatis
-- maps it as a plain String and Jackson parses it in MatchRule.
ALTER TABLE lf_short_link ADD COLUMN tags VARCHAR(512) NULL;
ALTER TABLE lf_link_variant ADD COLUMN match_rule VARCHAR(1024) NULL;
