create table sbt_fingerprint_event(
  md5_digest varchar,
  status tinyint not null,
  when_happened timestamp
);
