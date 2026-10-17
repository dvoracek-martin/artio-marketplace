package io.artio.marketplace.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("marketplace-local-dev")
@EnableScheduling
public class DevAdminSchedulingConfiguration {}
