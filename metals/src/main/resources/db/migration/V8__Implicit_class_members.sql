-- Implicit class member information for providing completions
create table implicit_class_members(
  class_symbol varchar not null,
  param_type varchar not null,
  method_symbol varchar not null,
  method_name varchar not null,
  start_line int not null,
  start_character int not null,
  end_line int not null,
  end_character int not null,
  path varchar not null,
  jar int,
  foreign key (jar) references indexed_jar (id) on delete cascade
);

create index implicit_class_members_jar on implicit_class_members(jar);
create index implicit_class_members_param_type on implicit_class_members(param_type);

alter table indexed_jar
add implicit_class_members_indexed bit;

