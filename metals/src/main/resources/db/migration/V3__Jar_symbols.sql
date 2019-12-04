-- Indexed jars, the MD5 digest of path, modified time and size as key
create table indexed_jar(
  id int auto_increment,
  md5 varchar primary key
);
-- Top Level Symbols per jar, allow for multiple jars with same symbols and paths
create table toplevel_symbol(
  symbol varchar not null,
  path varchar not null,
  jar int,
  foreign key (jar) references indexed_jar (id) on delete cascade,
  primary key (jar, path, symbol)
);
-- Create index to speedup lookup of jar symbols
create index toplevel_symbol_jar on toplevel_symbol(jar);
