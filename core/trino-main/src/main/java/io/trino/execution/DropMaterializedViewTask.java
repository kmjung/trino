/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.Metadata;
import io.trino.metadata.QualifiedObjectName;
import io.trino.metadata.TableHandle;
import io.trino.security.AccessControl;
import io.trino.sql.tree.DropMaterializedView;
import io.trino.sql.tree.Expression;
import io.trino.transaction.TransactionManager;

import java.util.List;
import java.util.Optional;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static io.trino.metadata.MetadataUtil.createQualifiedObjectName;
import static io.trino.spi.StandardErrorCode.TABLE_NOT_FOUND;
import static io.trino.sql.analyzer.SemanticExceptions.semanticException;

public class DropMaterializedViewTask
        implements DataDefinitionTask<DropMaterializedView>
{
    @Override
    public String getName()
    {
        return "DROP MATERIALIZED VIEW";
    }

    @Override
    public ListenableFuture<Void> execute(
            DropMaterializedView statement,
            TransactionManager transactionManager,
            Metadata metadata,
            AccessControl accessControl,
            QueryStateMachine stateMachine,
            List<Expression> parameters,
            WarningCollector warningCollector)
    {
        Session session = stateMachine.getSession();
        QualifiedObjectName name = createQualifiedObjectName(session, statement, statement.getName());

        if (!metadata.isMaterializedView(session, name)) {
            if (!statement.isExists()) {
                if (metadata.isView(session, name)) {
                    throw semanticException(
                            TABLE_NOT_FOUND,
                            statement,
                            "Materialized view '%s' does not exist, but a view with that name exists. Did you mean DROP VIEW %s?", name, name);
                }
                Optional<TableHandle> table = metadata.getTableHandle(session, name);
                if (table.isPresent()) {
                    throw semanticException(
                            TABLE_NOT_FOUND,
                            statement,
                            "Materialized view '%s' does not exist, but a table with that name exists. Did you mean DROP TABLE %s?", name, name);
                }
                throw semanticException(TABLE_NOT_FOUND, statement, "Materialized view '%s' does not exist", name);
            }
            return immediateVoidFuture();
        }

        accessControl.checkCanDropMaterializedView(session.toSecurityContext(), name);

        metadata.dropMaterializedView(session, name);

        return immediateVoidFuture();
    }
}
