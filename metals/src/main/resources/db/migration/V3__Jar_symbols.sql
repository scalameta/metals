-- Do not allow more than one combination of path and digest
create table indexed_jar(
  id int auto_increment,
  path varchar not null,
  last_modified bigint not null,
  size bigint not null,
  primary key (path, last_modified, size)
);
-- Allow for multiple jars with same symbols and paths
create table toplevel_symbol(
  symbol varchar not null,
  path varchar not null,
  jar int,
  primary key (jar, path, symbol)
);
-- Create index to speedup lookup of jar symbols
create index toplevel_symbol_jar on toplevel_symbol(jar);
