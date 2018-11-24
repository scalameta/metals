drop table if exists sbt_fingerprint_event;
drop table if exists sbt_checksum;

create table sbt_digest(
  md5 varchar,
  status tinyint not null,
  when_recorded timestamp
);
