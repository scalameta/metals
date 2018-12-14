-- For each unique combination of installed build servers we select one server.
-- The md5 checksum is computed from the names of the installed build servers, and
-- the selected server is the server which the user chose for this workspace.
create table chosen_build_server(
  md5 varchar primary key,
  selected_server varchar,
  when_recorded timestamp
);

