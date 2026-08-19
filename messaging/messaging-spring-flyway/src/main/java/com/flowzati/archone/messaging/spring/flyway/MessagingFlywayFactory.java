package com.flowzati.archone.messaging.spring.flyway;

import com.flowzati.archone.messaging.jdbc.MessagingSchema;
import com.flowzati.archone.messaging.jdbc.MessagingTableNames;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Creates, but deliberately does not run, a dedicated messaging Flyway instance. */
@FunctionalInterface
public interface MessagingFlywayFactory {

    String DEFAULT_SCHEMA = "archone_messaging";

    Flyway create(DataSource dataSource, MessagingSchema schema, MessagingTableNames tableNames);

    default Flyway create(DataSource dataSource) {
        return create(dataSource, MessagingSchema.named(DEFAULT_SCHEMA), MessagingTableNames.defaults());
    }
}
