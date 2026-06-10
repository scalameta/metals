-- Toplevel members of package objects now include type members inherited
-- from mixin parents (issue #2583), so previously indexed jars are stale.
-- Clearing the flag makes the indexer re-index toplevel members on demand.
delete from toplevel_members;

update indexed_jar
set toplevel_members_indexed = false;
