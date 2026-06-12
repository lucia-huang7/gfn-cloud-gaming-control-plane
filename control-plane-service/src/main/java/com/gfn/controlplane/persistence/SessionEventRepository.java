package com.gfn.controlplane.persistence;

import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionEventRepository extends CassandraRepository<SessionEvent, SessionEventKey> {
}

