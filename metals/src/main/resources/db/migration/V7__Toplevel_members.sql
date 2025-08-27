-- Toplevel member information, e.g. symbol: "a/Foo#Bar#", range: "1:5-1:8"
create table toplevel_members(
  symbol varchar not null,
  start_line int not null,
  start_character int not null,
  end_line int not null,
  end_character int not null,
  path varchar not null,
  kind int not null,
  jar int,
  foreign key (jar) references indexed_jar (id) on delete cascade
);

create index toplevel_members_jar on toplevel_members(jar);

alter table indexed_jar
add toplevel_members_indexed bit;
