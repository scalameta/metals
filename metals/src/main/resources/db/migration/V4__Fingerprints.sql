-- Fingerprints saved between invocations
create table fingerprints(
  path varchar not null,
  text varchar not null,
  md5 varchar not null,
  id int auto_increment unique
);
