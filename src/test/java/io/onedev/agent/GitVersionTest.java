package io.onedev.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitVersionTest {

	@Test
	public void shouldCompareVersionCorrectly() {
		assertTrue(new GitVersion("1.8.3.msysgit.0").isOlderThan(new GitVersion("1.8.4.0")));
		assertTrue(new GitVersion("1.8.3").isNotOlderThan(new GitVersion("1.8.3.0")));
		assertTrue(new GitVersion("1.8.3.0.0").isNotOlderThan(new GitVersion("1.8.3.0")));
		assertFalse(new GitVersion("1.8.3.0.0").isNotOlderThan(new GitVersion("1.8.3.2")));
		assertTrue(new GitVersion("1.9.3.0.0").isNewerThan(new GitVersion("1.8.3.2")));
		assertTrue(new GitVersion("1.9").isNewerThan(new GitVersion("1.8.3.2")));
		assertTrue(new GitVersion("1.8.3.2.0.0.1").isNewerThan(new GitVersion("1.8.3.2")));
	}

	@Test
	public void shouldHandleMsysgitCorrectly() {
		assertTrue(new GitVersion("1.8.3.msysgit.0").isMsysgit());
	}
	
	@Test
	public void shouldConvertToStringCorrectly() {
		assertEquals(new GitVersion("1.8.3.msysgit.0").toString(), "1.8.3.0");
		assertEquals(new GitVersion("1.8.3.05").toString(), "1.8.3.5");
	}	

}
