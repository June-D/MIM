package com.junesoon.mim;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableTransactionManagement
@EnableScheduling
@MapperScan("com.junesoon.mim.mapper")
@SpringBootApplication
public class MIMServerStarter {

    public static void main(String[] args) {
        SpringApplication.run(MIMServerStarter.class, args);
    }

}
