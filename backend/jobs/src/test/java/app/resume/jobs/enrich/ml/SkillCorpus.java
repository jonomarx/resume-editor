package app.resume.jobs.enrich.ml;

import app.resume.jobs.enrich.Slugs;
import app.resume.jobs.enrich.TaxonomyAliases;
import app.resume.jobs.enrich.VocabularyTagMatcher;
import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.util.Span;

/**
 * The weakly supervised training corpus for contender C. There is no real unlabelled posting corpus in the repository, so the
 * text is generated: sentence templates in the style of job postings, with technology names and their open-taxonomy aliases
 * (Wikidata, Linguist, O*NET) inserted at known positions, plus trap sentences that use ambiguous words as ordinary words. A
 * generated sentence becomes a training example only if the rules matcher (contender A, with the taxonomy aliases) finds
 * exactly the inserted tags in it: the labels are the matcher's, which is the weak supervision. The vocabulary is the
 * candidate list of tools/taxonomy/candidate-tags.txt; the evaluation fixtures are written separately and
 * {@code SkillTaggerTests} checks that no evaluation sentence appears here.
 */
final class SkillCorpus {
    static final long SEED = 20261026L;
    static final int SENTENCES = 9_000;

    /** {1}, {2}, {3} are skill slots. */
    static final List<String> POSITIVE = List.of(
            "Experience with {1} is required.", "Experience with {1} and {2}.", "Experience with {1}, {2} and {3}.",
            "Proficiency in {1}, {2} or {3}.", "Strong {1} skills.", "Strong knowledge of {1}.", "Knowledge of {1} and {2} is a plus.",
            "Familiarity with {1}.", "Familiar with {1} or {2}", "You will write services in {1}.", "Build and maintain applications in {1} and {2}.",
            "We use {1}, {2} and {3} to build our platform.", "Our stack: {1}, {2}, {3}", "Tech stack: {1}, {2}", "Languages: {1}, {2}, {3}",
            "Nice to have: {1}", "Nice to have: experience with {1} or {2}.", "Preferred: {1}", "Bonus points for {1}.", "Hands-on experience with {1}.",
            "{1} experience", "{1} programming", "Working knowledge of {1}", "Proficient in {1} and {2}", "Skills in {1}, {2} and {3}.",
            "Develop features using {1} and {2}.", "Deploy services with {1} and {2}.", "Comfortable with {1}.", "Understanding of {1} concepts.",
            "Expertise in {1}", "Experience developing in {1}.", "Coding in {1}", "- {1}", "- {1}, {2}", "* {1} and {2}", "• {1}",
            "Write clean, tested code in {1}.", "You know {1} well.", "Build data pipelines with {1}.", "Work with {1} and {2} every day.",
            "Minimum qualifications: {1} and {2}.", "Experience using {1} for production systems.", "Ability to learn {1} quickly.",
            "A background in {1}.", "Contribute to our {1} codebase.", "Our engineers work in {1}, {2} and {3}.",
            "Senior {1} developer needed for a summer internship.", "{1} Developer Intern", "Intern, {1} Platform Team", "{1}/{2} experience preferred",
            "Experience with {1} (or {2}).", "Knowledge of {1}; {2} is a plus.");

    /** Sentences with no skills; the ambiguous words below are used as ordinary words, which the matcher leaves unlabelled. */
    static final List<String> NEGATIVE = List.of(
            "Go to market with new products.", "Join us in Spring 2027.", "Benefits: Spring break off.", "We value: Swift response times.",
            "Go beyond expectations.", "Rust is not required here.", "No Java experience required.", "Our R&D team is growing.",
            "Work with our C-level executives.", "A go-getter attitude.", "Plan A or plan B.", "Vitamin C, and more.",
            "The internship runs for twelve weeks.", "Apply by October 30.", 
            "Competitive pay and a relocation stipend.", "Free lunch every Thursday.", "You will work with a team of ten engineers.",
            "Join a mission-driven company.", "Currently pursuing a degree in Computer Science or a related field.",
            "Excellent communication skills.", "Ability to work independently.", "Graduating in 2028 or later.",
            "Must be authorized to work in the United States.",
            "Interns present their work at the end of the summer.", "We offer flexible hours and a learning budget.",
            "Based in Seattle, WA, with a hybrid schedule.", "Our offices have a quiet room and a kitchen.", "Reach out to your recruiter.",
            "Let's go, team!", "Spring forward with us.", "The Swift current carried the boat.", "Rust never sleeps.",
            "Have a great spring break.", "Participate in code reviews and weekly planning.", "Collaborate with designers and product managers.");

    private SkillCorpus() {}

    static List<String> candidateNames() throws IOException {
        try (InputStream in = SkillCorpus.class.getResourceAsStream("/ml/training-vocabulary.txt")) {
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank() && !line.startsWith("#")) names.add(line.strip());
            }
            return names;
        }
    }

    static List<VocabularyTag> vocabulary() throws IOException {
        List<VocabularyTag> v = new ArrayList<>();
        long id = 1;
        for (String n : candidateNames()) v.add(new VocabularyTag(id++, n, Slugs.slug(n), VocabularyCategory.LANGUAGE, List.of()));
        return v;
    }

    static final List<String> EVALUATION_FIXTURES = List.of("labelled-postings.json", "labelled-postings-long.json", "labelled-postings-aliased.json");

    /** Every sentence (lower-cased, tokens joined by spaces) of the evaluation fixtures: never allowed into the training data. */
    static Set<String> evaluationSentences() throws IOException {
        Set<String> out = new HashSet<>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String f : EVALUATION_FIXTURES) {
            try (InputStream in = SkillCorpus.class.getResourceAsStream("/fixtures/enrichment/" + f)) {
                for (var p : mapper.readTree(in).get("postings")) {
                    for (String[] s : SkillTokenizer.sentences(p.get("text").asText())) out.add(String.join(" ", s).toLowerCase());
                    for (String[] s : SkillTokenizer.sentences(p.get("title").asText())) out.add(String.join(" ", s).toLowerCase());
                }
            }
        }
        return out;
    }

    /** Training sentences as NameSamples, deterministic for the fixed seed; sentences equal to an evaluation sentence are skipped. */
    static List<NameSample> samples() throws IOException {
        Set<String> forbidden = evaluationSentences();
        List<VocabularyTag> vocab = vocabulary();
        TaxonomyAliases taxonomy = TaxonomyAliases.shared(TaxonomyAliases.ALL_SOURCES);
        VocabularyTagMatcher labeller = new VocabularyTagMatcher(vocab, taxonomy);
        List<List<String>> surfaces = new ArrayList<>();
        for (VocabularyTag t : vocab) {
            List<String> forms = new ArrayList<>();
            forms.add(t.name());
            forms.addAll(taxonomy.aliasesFor(t));
            surfaces.add(forms);
        }
        Random rnd = new Random(SEED);
        List<NameSample> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int attempts = 0;
        while (out.size() < SENTENCES && attempts++ < SENTENCES * 20) {
            boolean negative = rnd.nextInt(5) == 0;
            String template = negative ? NEGATIVE.get(rnd.nextInt(NEGATIVE.size())) : POSITIVE.get(rnd.nextInt(POSITIVE.size()));
            Set<Long> inserted = new HashSet<>();
            List<String> skillSurfaces = new ArrayList<>();
            String text = template;
            for (int slot = 1; slot <= 3; slot++) {
                if (!text.contains("{" + slot + "}")) continue;
                int idx = rnd.nextInt(vocab.size());
                List<String> forms = surfaces.get(idx);
                String form = forms.get(rnd.nextInt(forms.size()));
                if (rnd.nextInt(8) == 0 && form.equals(vocab.get(idx).name())) form = form.toLowerCase(java.util.Locale.ROOT);
                if (skillSurfaces.contains(form)) form = vocab.get(idx).name();
                inserted.add(vocab.get(idx).id());
                skillSurfaces.add(form);
                text = text.replace("{" + slot + "}", form);
            }
            if (!seen.add(text)) continue;
            if (forbidden.contains(String.join(" ", SkillTokenizer.tokens(text, SkillTokenizer.MAX_SENTENCE_TOKENS)).toLowerCase())) continue;
            // Weak labels: keep the sentence only if the matcher agrees with what was inserted.
            if (!labeller.match(text, VocabularyTagMatcher.Mode.GUARDED).equals(inserted)) continue;
            NameSample s = sample(text, skillSurfaces);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static NameSample sample(String text, List<String> skillSurfaces) {
        List<String> tokens = SkillTokenizer.tokens(text, SkillTokenizer.MAX_SENTENCE_TOKENS);
        List<Span> spans = new ArrayList<>();
        int searchFrom = 0;
        for (String surface : skillSurfaces) {
            List<String> st = SkillTokenizer.tokens(surface, 20);
            int at = indexOf(tokens, st, searchFrom);
            if (at < 0) return null;
            spans.add(new Span(at, at + st.size(), "skill"));
            searchFrom = at + st.size();
        }
        spans.sort(java.util.Comparator.comparingInt(Span::getStart));
        return new NameSample(tokens.toArray(new String[0]), spans.toArray(new Span[0]), true);
    }

    private static int indexOf(List<String> tokens, List<String> part, int from) {
        outer:
        for (int i = from; i + part.size() <= tokens.size(); i++) {
            for (int k = 0; k < part.size(); k++) if (!tokens.get(i + k).equals(part.get(k))) continue outer;
            return i;
        }
        return -1;
    }
}
