package dev.conduit.event;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Registers an HQL function {@code byte_length(x)} that renders to PostgreSQL {@code octet_length(x)}
 * and returns an {@code Integer}.
 *
 * <p>Why this exists: Hibernate's built-in {@code octet_length()}/{@code length()} HQL functions are
 * typed for strings/CLOB and reject a {@code byte[]} (bytea) argument ("Parameter 1 ... has type
 * STRING_OR_CLOB, but argument is of type byte[]"). The inspector list needs the payload's byte size
 * without loading the bytes, so we register a pattern function with no argument type-check.
 *
 * <p>Wired via {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public class BinaryLengthFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        functionContributions.getFunctionRegistry().registerPattern(
                "byte_length",
                "octet_length(?1)",
                functionContributions.getTypeConfiguration()
                        .getBasicTypeRegistry()
                        .resolve(StandardBasicTypes.INTEGER));
    }
}
