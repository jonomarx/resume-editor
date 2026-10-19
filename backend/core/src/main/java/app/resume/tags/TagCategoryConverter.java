package app.resume.tags;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Query parameters use the contract's lower-case values ("role_type"), not the Java constant names. */
@Component
class TagCategoryConverter implements Converter<String, TagCategory> {
    @Override
    public TagCategory convert(String source) {
        return TagCategory.fromValue(source);
    }
}
