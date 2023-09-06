-- Type hierarchy information, e.g. symbol: "a/MyException#", extended_name: "Exception"
create table type_hierarchy(
  symbol varchar not null,
  extended_name varchar not null,
  extended_name_offset int,
  path varchar not null,
  jar int,
  foreign key (jar) references indexed_jar (id) on delete cascade,
  primary key (jar, path, symbol, extended_name, extended_name_offset)
);

create index type_hierarchy_jar on type_hierarchy(jar);

alter table indexed_jar
add type_hierarchy_indexed bit
