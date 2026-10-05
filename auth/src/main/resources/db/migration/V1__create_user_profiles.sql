CREATE TABLE user_profiles (
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    user_id TEXT,
    tenant_id TEXT,
    email TEXT,
    name TEXT,
    PRIMARY KEY (issuer, subject)
);
