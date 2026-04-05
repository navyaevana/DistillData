package com.distilldata.demo.repository;

import com.distilldata.demo.entity.DatasetMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DatasetMetadataRepository extends JpaRepository<DatasetMetadata, Long> {
}