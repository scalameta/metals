-- Type hierarchy information, e.g. symbol: "a/MyException#", extended_name: "Exception"
create table type_hierarchy(
  symbol varchar not null,
  parent_name varchar not null,
  path varchar not null,
  jar int,
  is_resolved bit,
  foreign key (jar) references indexed_jar (id) on delete cascade
);

create index type_hierarchy_jar on type_hierarchy(jar);

alter table indexed_jar
add type_hierarchy_indexed bit
