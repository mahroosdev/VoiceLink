package com.mahroosdev.voicelink.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PasswordResetExecutorConfig {
    @Bean
    @Profile("!prod & !test")
    PasswordResetNotifier disabledLocalNotifier() {
        return (recipient, resetUrl) -> { throw new PasswordResetMailException(); };
    }

    @Bean("passwordResetExecutor")
    TaskExecutor passwordResetExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("password-reset-mail-");
        executor.initialize();
        return executor;
    }
}
