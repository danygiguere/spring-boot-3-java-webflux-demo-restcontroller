package com.example.restcontrollerdemo.repository;

import com.example.restcontrollerdemo.domain.entity.Enterprise;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface EnterpriseRepository extends ReactiveCrudRepository<Enterprise, Long> {}
