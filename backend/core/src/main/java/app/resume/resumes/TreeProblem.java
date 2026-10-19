package app.resume.resumes;

/** One validation problem; `field` is a node id (token rules) or a schema location, as in the contract's error `details`. */
public record TreeProblem(String field, String message) {}
