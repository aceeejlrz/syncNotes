package com.jezrel.syncnotes;

import org.springframework.boot.SpringApplication;

public class TestSyncNotesApplication {

	public static void main(String[] args) {
		SpringApplication.from(SyncNotesApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
