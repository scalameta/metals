-- Implicit class symbols for type-based completion matching
-- Only stores class symbols; presentation compiler resolves methods and types
create table implicit_class_members(
  class_symbol varchar not null,
  jar int,
  foreign key (jar) references indexed_jar (id) on delete cascade
);

create index implicit_class_members_jar on implicit_class_members(jar);
create index implicit_class_members_symbol on implicit_class_members(class_symbol);

alter table indexed_jar
add implicit_class_members_indexed bit;

