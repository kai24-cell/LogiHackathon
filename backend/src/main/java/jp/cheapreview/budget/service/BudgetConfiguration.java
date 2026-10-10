package jp.cheapreview.budget.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BudgetSettings.class)
class BudgetConfiguration {}
