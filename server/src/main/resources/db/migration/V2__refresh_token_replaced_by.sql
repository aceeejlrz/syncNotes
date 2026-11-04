-- Which token replaced this one when it was rotated (section 10.2). A token revoked any other way,
-- by logout or by hand, has none, and so gets no grace period.
ALTER TABLE refresh_tokens ADD COLUMN replaced_by UUID;
