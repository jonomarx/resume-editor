package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import app.resume.tags.TagCategory;
import app.resume.tags.TagService;
import app.resume.vocabulary.TagVocabulary;
import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The read-only vocabulary port in `common`, implemented by core on top of TagService. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TagVocabularyPortTests {
    @Autowired MockMvc mvc;
    @Autowired TagService tags;
    @Autowired TagVocabulary vocabulary;

    long newUser() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
        String body = mvc.perform(get("/api/me").cookie(s)).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void lookupBySlugNameAndAliasIsCaseInsensitiveAndNormalizedLikeTheVocabulary() throws Exception {
        long u = newUser();
        long cpp = tags.getOrCreate(u, "C++", TagCategory.LANGUAGE).tag().id();
        long pg = tags.getOrCreate(u, "PostgreSQL", TagCategory.LANGUAGE).tag().id();
        tags.addAlias(u, "postgresql", "Postgres");

        assertThat(vocabulary.findBySlug(u, "c-plus-plus")).get().extracting(VocabularyTag::id).isEqualTo(cpp);
        assertThat(vocabulary.findBySlug(u, "Postgres")).as("an alias is not a slug").isEmpty();
        assertThat(vocabulary.findBySlug(u, "not a slug")).isEmpty();
        assertThat(vocabulary.findBySlug(u, null)).isEmpty();

        assertThat(vocabulary.resolve(u, "C++")).contains(cpp); // name; + maps to -plus-
        assertThat(vocabulary.resolve(u, "  postgresql ")).contains(pg); // name, trimmed, any case
        assertThat(vocabulary.resolve(u, "POSTGRES")).contains(pg); // alias, case-insensitive
        assertThat(vocabulary.resolve(u, "c-plus-plus")).contains(cpp); // slug
        VocabularyTag found = vocabulary.find(u, "postgres").orElseThrow();
        assertThat(found.slug()).isEqualTo("postgresql");
        assertThat(found.name()).isEqualTo("PostgreSQL");
        assertThat(found.category()).isEqualTo(VocabularyCategory.LANGUAGE);
        assertThat(found.aliases()).containsExactly("Postgres");
    }

    @Test
    void unknownBlankAndNullResolveToNothingAndNothingIsCreated() throws Exception {
        long u = newUser();
        assertThat(vocabulary.resolve(u, "Rust")).isEmpty();
        assertThat(vocabulary.find(u, "Rust")).isEmpty();
        assertThat(vocabulary.find(u, "   ")).isEmpty();
        assertThat(vocabulary.find(u, null)).isEmpty();
        assertThat(vocabulary.find(u, "!!!")).isEmpty(); // slug normalizes to nothing
        assertThat(vocabulary.list(u, null)).as("reads never create tags").isEmpty();
    }

    @Test
    void theVocabularyIsPerUser() throws Exception {
        long a = newUser();
        long b = newUser();
        tags.getOrCreate(a, "Kotlin", TagCategory.LANGUAGE);
        tags.addAlias(a, "kotlin", "Kt");
        assertThat(vocabulary.resolve(b, "Kotlin")).isEmpty();
        assertThat(vocabulary.resolve(b, "Kt")).isEmpty();
        assertThat(vocabulary.findBySlug(b, "kotlin")).isEmpty();
        assertThat(vocabulary.list(b, null)).isEmpty();
        assertThat(vocabulary.list(a, null)).hasSize(1);
    }

    @Test
    void listFiltersByCategoryOrderedByNameAndIsImmutable() throws Exception {
        long u = newUser();
        tags.getOrCreate(u, "React", TagCategory.FRAMEWORK);
        tags.getOrCreate(u, "Java", TagCategory.LANGUAGE);
        tags.getOrCreate(u, "Go", TagCategory.LANGUAGE);
        tags.getOrCreate(u, "Fintech", TagCategory.DOMAIN);
        tags.getOrCreate(u, "Backend", TagCategory.ROLE_TYPE);
        assertThat(vocabulary.list(u, null)).extracting(VocabularyTag::name).containsExactly("Backend", "Fintech", "Go", "Java", "React");
        assertThat(vocabulary.list(u, VocabularyCategory.LANGUAGE)).extracting(VocabularyTag::slug).containsExactly("go", "java");
        assertThat(vocabulary.list(u, VocabularyCategory.ROLE_TYPE)).extracting(VocabularyTag::category)
                .containsOnly(VocabularyCategory.ROLE_TYPE);
        VocabularyTag t = vocabulary.findBySlug(u, "java").orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.aliases().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyCommonCategoryMapsToTheApiSpelling() {
        for (VocabularyCategory c : VocabularyCategory.values()) {
            assertThat(TagCategory.fromValue(c.value()).name()).isEqualTo(c.name());
        }
    }
}
