CREATE KEYSPACE IF NOT EXISTS gfn_control_plane
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'datacenter1': 1
}
AND durable_writes = true;

CREATE TABLE IF NOT EXISTS gfn_control_plane.session_events_by_session (
  session_id text,
  created_at timestamp,
  event_id uuid,
  event_type text,
  region text,
  gpu_profile text,
  node_id text,
  PRIMARY KEY ((session_id), created_at, event_id)
) WITH CLUSTERING ORDER BY (created_at ASC, event_id ASC)
  AND default_time_to_live = 2592000
  AND compaction = {
    'class': 'TimeWindowCompactionStrategy',
    'compaction_window_unit': 'DAYS',
    'compaction_window_size': '1'
  };

