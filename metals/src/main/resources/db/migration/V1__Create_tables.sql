create table sbt_checksum(
  md5_digest varchar primary key,
  status tinyint not null
);

create table dependency_source(
  text_document_uri varchar primary key,
  build_target_uri varchar not null
);

