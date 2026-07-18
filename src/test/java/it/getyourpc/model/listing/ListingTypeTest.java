package it.getyourpc.model.listing;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingTypeTest {
    @Test
    void acceptsCaseInsensitiveWebValues() {
        assertThat(ListingType.from("desktop")).isEqualTo(ListingType.DESKTOP);
        assertThat(ListingType.from("LAPTOP")).isEqualTo(ListingType.LAPTOP);
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> ListingType.from("tablet"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
