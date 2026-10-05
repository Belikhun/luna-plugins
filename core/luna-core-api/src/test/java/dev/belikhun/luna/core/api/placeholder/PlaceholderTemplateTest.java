package dev.belikhun.luna.core.api.placeholder;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaceholderTemplateTest {
	@Test
	void unresolvedRefsKeepTheirTextAndValuesAreNeverRescanned() {
		PlaceholderTemplate template = PlaceholderTemplate.parse("[%a_x%] %b_y% <c_z> {d_w}", EnumSet.allOf(PlaceholderSyntax.class));
		Map<String, String> values = Map.of("a_x", "100% %b_y%", "c_z", "<red>");

		String rendered = template.render(ref -> values.get(ref.normalizedIdentifier()));

		assertEquals("[100% %b_y%] %b_y% <red> {d_w}", rendered);
		assertEquals(4, template.refs().size());
	}

	@Test
	void layersResolveInOrder() {
		PlaceholderTemplate template = PlaceholderTemplate.parse("%a_x% %a_y%", EnumSet.of(PlaceholderSyntax.PERCENT));

		String rendered = template.render(
			ref -> ref.key().equals("x") ? "first" : null,
			ref -> "second"
		);

		assertEquals("first second", rendered);
	}

	@Test
	void literalTemplateIsRecognised() {
		assertTrue(PlaceholderTemplate.parse("no placeholders here, 100% sure", EnumSet.allOf(PlaceholderSyntax.class)).isLiteral());
	}
}
