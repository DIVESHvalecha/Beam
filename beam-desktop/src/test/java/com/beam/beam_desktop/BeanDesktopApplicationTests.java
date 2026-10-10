package com.beam.beam_desktop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(args = "--help")
class BeanDesktopApplicationTests {

	@Test
	void contextLoads() {
		// --help exercises the runner without opening the TUI (no tty in surefire).
	}

}
