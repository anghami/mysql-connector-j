/*
  Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.

  The MySQL Connector/J is licensed under the terms of the GPLv2
  <http://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, like most MySQL Connectors.
  There are special exceptions to the terms and conditions of the GPLv2 as it is applied to
  this software, see the FOSS License Exception
  <http://www.mysql.com/about/legal/licensing/foss-exception.html>.

  This program is free software; you can redistribute it and/or modify it under the terms
  of the GNU General Public License as published by the Free Software Foundation; version 2
  of the License.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with this
  program; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth
  Floor, Boston, MA 02110-1301  USA

 */

package com.mysql.cj.mysqlx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Spliterators;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.mysql.cj.api.ProfilerEventHandler;
import com.mysql.cj.api.Session;
import com.mysql.cj.api.conf.PropertySet;
import com.mysql.cj.api.exceptions.ExceptionInterceptor;
import com.mysql.cj.api.io.Protocol;
import com.mysql.cj.api.io.ServerSession;
import com.mysql.cj.api.log.Log;
import com.mysql.cj.api.result.Row;
import com.mysql.cj.api.result.RowList;
import com.mysql.cj.api.x.DataStatement.Reducer;
import com.mysql.cj.api.x.FetchedDocs;
import com.mysql.cj.api.x.FetchedRows;
import com.mysql.cj.api.x.SqlResult;
import com.mysql.cj.core.ConnectionString;
import com.mysql.cj.core.ServerVersion;
import com.mysql.cj.core.conf.DefaultPropertySet;
import com.mysql.cj.core.conf.PropertyDefinitions;
import com.mysql.cj.core.exceptions.CJCommunicationsException;
import com.mysql.cj.core.io.DbDocValueFactory;
import com.mysql.cj.core.io.LongValueFactory;
import com.mysql.cj.core.io.StatementExecuteOk;
import com.mysql.cj.core.io.StringValueFactory;
import com.mysql.cj.core.result.Field;
import com.mysql.cj.mysqlx.devapi.DbDocsImpl;
import com.mysql.cj.mysqlx.devapi.DevapiRowFactory;
import com.mysql.cj.mysqlx.devapi.RowsImpl;
import com.mysql.cj.mysqlx.devapi.SqlDataResult;
import com.mysql.cj.mysqlx.devapi.SqlUpdateResult;
import com.mysql.cj.mysqlx.io.MysqlxProtocol;
import com.mysql.cj.mysqlx.io.MysqlxProtocolFactory;
import com.mysql.cj.mysqlx.io.ResultListener;
import com.mysql.cj.mysqlx.io.ResultStreamer;
import com.mysql.cj.x.json.DbDoc;

/**
 * @todo
 */
public class MysqlxSession implements Session {

    /**
     * A function to create a result set from the metadata, row list and ok packet (curried representation).
     *
     * @param<T> the type of the result that this constructor will create
     */
    public static interface ResultCtor<T> extends Function<ArrayList<Field>, BiFunction<RowList, Supplier<StatementExecuteOk>, T>> {}

    private MysqlxProtocol protocol;
    private ResultStreamer currentResult;
    private String host;
    private int port;

    public MysqlxSession(Properties properties) {

        PropertySet pset = new DefaultPropertySet();
        pset.initializeProperties(properties);

        // create protocol instance
        this.host = ConnectionString.normalizeHost(properties.getProperty(PropertyDefinitions.HOST_PROPERTY_KEY));
        this.port = ConnectionString.parsePortNumber(properties.getProperty(PropertyDefinitions.PORT_PROPERTY_KEY, "33060"));

        this.protocol = MysqlxProtocolFactory.getInstance(this.host, this.port, pset);
    }

    public String getHost() {
        return this.host;
    }

    public int getPort() {
        return this.port;
    }

    public PropertySet getPropertySet() {
        return this.protocol.getPropertySet();
    }

    public Protocol getProtocol() {
        throw new NullPointerException("TODO: You are not allowed to have my protocol");
    }

    public void changeUser(String user, String password, String database) {
        this.protocol.sendSaslMysql41AuthStart();
        byte[] salt = this.protocol.readAuthenticateContinue();
        this.protocol.sendSaslMysql41AuthContinue(user, password, salt, database);
        this.protocol.readAuthenticateOk();
        setupInternalState();
    }

    /**
     * Setup internal state of the session.
     */
    private void setupInternalState() {
        this.protocol.setMaxAllowedPacket((int) queryForLong("select @@mysqlx_max_allowed_packet"));
    }

    public ExceptionInterceptor getExceptionInterceptor() {
        throw new NullPointerException("TODO: You are not allowed to have this");
    }

    public void setExceptionInterceptor(ExceptionInterceptor exceptionInterceptor) {
        throw new NullPointerException("TODO: I don't need your stinkin exception interceptor");
    }

    public boolean characterSetNamesMatches(String mysqlEncodingName) {
        throw new NullPointerException("TODO: don't implement this method here");
    }

    public boolean inTransactionOnServer() {
        throw new NullPointerException("TODO: who wants to know? Also, check NEW tx state in OK packet extensions");
    }

    public String getServerVariable(String name) {
        throw new NullPointerException("TODO: ");
    }

    public Map<String, String> getServerVariables() {
        throw new NullPointerException("TODO: ");
    }

    public void setServerVariables(Map<String, String> serverVariables) {
        throw new NullPointerException("TODO: ");
    }

    public void abortInternal() {
        throw new NullPointerException("TODO: REPLACE ME WITH close() unless there's different semantics here");
    }

    public void quit() {
        throw new NullPointerException("TODO: REPLACE ME WITH close() unless there's different semantics here");
    }

    public void forceClose() {
        throw new NullPointerException("TODO: REPLACE ME WITH close() unless there's different semantics here");
    }

    public ServerVersion getServerVersion() {
        throw new NullPointerException("TODO: isn't this in server session?");
    }

    public boolean versionMeetsMinimum(int major, int minor, int subminor) {
        throw new NullPointerException("TODO: ");
    }

    public long getThreadId() {
        return this.protocol.getClientId();
    }

    public boolean isSetNeededForAutoCommitMode(boolean autoCommitFlag) {
        throw new NullPointerException("TODO: ");
    }

    /**
     * @todo
     * @todo
     * @todo
     * @todo
     * @todo
     * @todo
     */
    private void newCommand() {
        if (this.currentResult != null) {
            this.currentResult.finishStreaming();
            this.currentResult = null;
        }
    }

    public StatementExecuteOk addDocs(String schemaName, String collectionName, List<String> jsonStrings) {
        newCommand();
        this.protocol.sendDocInsert(schemaName, collectionName, jsonStrings);
        return this.protocol.readStatementExecuteOk();
    }

    public StatementExecuteOk insertRows(String schemaName, String tableName, InsertParams insertParams) {
        newCommand();
        this.protocol.sendRowInsert(schemaName, tableName, insertParams);
        return this.protocol.readStatementExecuteOk();
    }

    public StatementExecuteOk updateDocs(FilterParams filterParams, List<UpdateSpec> updates) {
        newCommand();
        this.protocol.sendDocUpdates(filterParams, updates);
        return this.protocol.readStatementExecuteOk();
    }

    public StatementExecuteOk updateRows(FilterParams filterParams, UpdateParams updateParams) {
        newCommand();
        this.protocol.sendRowUpdates(filterParams, updateParams);
        return this.protocol.readStatementExecuteOk();
    }

    public StatementExecuteOk deleteDocs(FilterParams filterParams) {
        newCommand();
        this.protocol.sendDocDelete(filterParams);
        return this.protocol.readStatementExecuteOk();
    }

    public StatementExecuteOk deleteRows(FilterParams filterParams) {
        newCommand();
        // TODO: this works because xplugin doesn't check dataModel on delete. it doesn't need to... protocol change?
        this.protocol.sendDocDelete(filterParams);
        return this.protocol.readStatementExecuteOk();
    }

    private <T extends ResultStreamer> T findInternal(FindParams findParams, ResultCtor<T> resultCtor) {
        newCommand();
        this.protocol.sendFind(findParams);
        // TODO: put characterSetMetadata somewhere useful
        ArrayList<Field> metadata = this.protocol.readMetadata("latin1");
        T res = resultCtor.apply(metadata).apply(this.protocol.getRowInputStream(metadata), this.protocol::readStatementExecuteOk);
        this.currentResult = res;
        return res;
    }

    public DbDocsImpl findDocs(FindParams findParams) {
        return findInternal(findParams, metadata -> (rows, task) -> new DbDocsImpl(rows, task));
    }

    public RowsImpl selectRows(FindParams findParams) {
        return findInternal(findParams, metadata -> (rows, task) -> new RowsImpl(metadata, rows, task));
    }

    public void createCollection(String schemaName, String collectionName) {
        newCommand();
        this.protocol.sendCreateCollection(schemaName, collectionName);
        this.protocol.readStatementExecuteOk();
    }

    public void dropCollection(String schemaName, String collectionName) {
        newCommand();
        this.protocol.sendDropCollection(schemaName, collectionName);
        this.protocol.readStatementExecuteOk();
    }

    public void dropCollectionIfExists(String schemaName, String collectionName) {
        if (tableExists(schemaName, collectionName)) {
            dropCollection(schemaName, collectionName);
        }
    }

    public StatementExecuteOk createCollectionIndex(String schemaName, String collectionName, CreateIndexParams params) {
        newCommand();
        this.protocol.sendCreateCollectionIndex(schemaName, collectionName, params);
        return this.protocol.readStatementExecuteOk();
    }

    public StatementExecuteOk dropCollectionIndex(String schemaName, String collectionName, String indexName) {
        newCommand();
        this.protocol.sendDropCollectionIndex(schemaName, collectionName, indexName);
        return this.protocol.readStatementExecuteOk();
    }

    private long queryForLong(String sql) {
        newCommand();
        this.protocol.sendSqlStatement(sql);
        // TODO: can use a simple default for this as we don't need metadata. need to prevent against exceptions though
        ArrayList<Field> metadata = this.protocol.readMetadata("latin1");
        long count = this.protocol.getRowInputStream(metadata).next().getValue(0, new LongValueFactory());
        this.protocol.readStatementExecuteOk();
        return count;
    }

    public long tableCount(String schemaName, String tableName) {
        StringBuilder stmt = new StringBuilder("select count(*) from ");
        stmt.append(ExprUnparser.quoteIdentifier(schemaName));
        stmt.append(".");
        stmt.append(ExprUnparser.quoteIdentifier(tableName));
        return queryForLong(stmt.toString());
    }

    public boolean schemaExists(String schemaName) {
        StringBuilder stmt = new StringBuilder("select count(*) from information_schema.schemata where schema_name = '");
        // TODO: verify quoting rules
        stmt.append(schemaName.replaceAll("'", "\\'"));
        stmt.append("'");
        return 1 == queryForLong(stmt.toString());
    }

    public boolean tableExists(String schemaName, String tableName) {
        StringBuilder stmt = new StringBuilder("select count(*) from information_schema.tables where table_schema = '");
        // TODO: verify quoting rules
        stmt.append(schemaName.replaceAll("'", "\\'"));
        stmt.append("' and table_name = '");
        stmt.append(tableName.replaceAll("'", "\\'"));
        stmt.append("'");
        return 1 == queryForLong(stmt.toString());
    }

    /**
     * Retrieve the list of objects in the given schema of the specified type. The type may be one of {COLLECTION, TABLE, VIEW}.
     *
     * @param schemaName
     *            schema to return object names from
     * @param type
     *            type of objects to return
     * @return object names
     */
    public List<String> getObjectNamesOfType(String schemaName, String type) {
        newCommand();
        this.protocol.sendListObjects(schemaName);
        // TODO: charactersetMetadata
        ArrayList<Field> metadata = this.protocol.readMetadata("latin1");
        Iterator<Row> ris = this.protocol.getRowInputStream(metadata);
        List<String> objectNames = StreamSupport.stream(Spliterators.spliteratorUnknownSize(ris, 0), false)
                .filter(r -> r.getValue(1, new StringValueFactory()).equals(type)).map(r -> r.getValue(0, new StringValueFactory()))
                .collect(Collectors.toList());
        this.protocol.readStatementExecuteOk();
        return objectNames;
    }

    public <RES_T, R> RES_T query(String sql, Function<Row, R> eachRow, Collector<R, ?, RES_T> collector) {
        newCommand();
        this.protocol.sendSqlStatement(sql);
        // TODO: characterSetMetadata
        ArrayList<Field> metadata = this.protocol.readMetadata("latin1");
        Iterator<Row> ris = this.protocol.getRowInputStream(metadata);
        RES_T result = StreamSupport.stream(Spliterators.spliteratorUnknownSize(ris, 0), false).map(eachRow).collect(collector);
        this.protocol.readStatementExecuteOk();
        return result;
    }

    public SqlResult executeSql(String sql, Object args) {
        newCommand();
        this.protocol.sendSqlStatement(sql, args);
        if (this.protocol.isResultPending()) {
            // TODO: put characterSetMetadata somewhere useful
            ArrayList<Field> metadata = this.protocol.readMetadata("latin1");
            SqlDataResult res = new SqlDataResult(metadata, this.protocol.getRowInputStream(metadata), this.protocol::readStatementExecuteOk);
            this.currentResult = res;
            return res;
        } else {
            return new SqlUpdateResult(this.protocol.readStatementExecuteOk());
        }
    }

    public CompletableFuture<SqlResult> asyncExecuteSql(String sql, Object args) {
        newCommand();
        // TODO: put characterSetMetadata somewhere useful
        return this.protocol.asyncExecuteSql(sql, args, "latin1");
    }

    public StatementExecuteOk update(String sql) {
        newCommand();
        this.protocol.sendSqlStatement(sql);
        return this.protocol.readStatementExecuteOk();
    }

    public void close() {
        try {
            newCommand();
            this.protocol.sendSessionClose();
            this.protocol.readOk();
        } finally {
            try {
                this.protocol.close();
            } catch (IOException ex) {
                throw new CJCommunicationsException(ex);
            }
        }
    }

    private <RES_T> CompletableFuture<RES_T> asyncFindInternal(FindParams findParams, ResultCtor<? extends RES_T> resultCtor) {
        CompletableFuture<RES_T> f = new CompletableFuture<>();
        ResultListener l = new ResultCreatingResultListener<RES_T>(resultCtor, f);
        newCommand();
        // TODO: put characterSetMetadata somewhere useful
        this.protocol.asyncFind(findParams, "latin1", l, f);
        return f;
    }

    public CompletableFuture<FetchedDocs> asyncFindDocs(FindParams findParams) {
        return asyncFindInternal(findParams, metadata -> (rows, task) -> new DbDocsImpl(rows, task));
    }

    public CompletableFuture<FetchedRows> asyncSelectRows(FindParams findParams) {
        return asyncFindInternal(findParams, metadata -> (rows, task) -> new RowsImpl(metadata, rows, task));
    }

    public <R> CompletableFuture<R> asyncFindDocsReduce(FindParams findParams, R id, Reducer<DbDoc, R> reducer) {
        CompletableFuture<R> f = new CompletableFuture<R>();
        ResultListener l = new RowWiseReducingResultListener<DbDoc, R>(id, reducer, f,
                (ArrayList<Field> _ignored_metadata) -> r -> r.getValue(0, new DbDocValueFactory()));
        newCommand();
        // TODO: put characterSetMetadata somewhere useful
        this.protocol.asyncFind(findParams, "latin1", l, f);
        return f;
    }

    public <R> CompletableFuture<R> asyncSelectRowsReduce(FindParams findParams, R id, Reducer<com.mysql.cj.api.x.Row, R> reducer) {
        CompletableFuture<R> f = new CompletableFuture<R>();
        ResultListener l = new RowWiseReducingResultListener<com.mysql.cj.api.x.Row, R>(id, reducer, f, DevapiRowFactory::new);
        newCommand();
        // TODO: put characterSetMetadata somewhere useful
        this.protocol.asyncFind(findParams, "latin1", l, f);
        return f;
    }

    public CompletableFuture<StatementExecuteOk> asyncAddDocs(String schemaName, String collectionName, List<String> jsonStrings) {
        newCommand();
        return this.protocol.asyncAddDocs(schemaName, collectionName, jsonStrings);
    }

    public CompletableFuture<StatementExecuteOk> asyncInsertRows(String schemaName, String tableName, InsertParams insertParams) {
        newCommand();
        return this.protocol.asyncInsertRows(schemaName, tableName, insertParams);
    }

    public CompletableFuture<StatementExecuteOk> asyncUpdateDocs(FilterParams filterParams, List<UpdateSpec> updates) {
        newCommand();
        return this.protocol.asyncUpdateDocs(filterParams, updates);
    }

    public CompletableFuture<StatementExecuteOk> asyncUpdateRows(FilterParams filterParams, UpdateParams updateParams) {
        newCommand();
        return this.protocol.asyncUpdateRows(filterParams, updateParams);
    }

    public CompletableFuture<StatementExecuteOk> asyncDeleteDocs(FilterParams filterParams) {
        newCommand();
        return this.protocol.asyncDeleteDocs(filterParams);
    }

    public CompletableFuture<StatementExecuteOk> asyncDeleteRows(FilterParams filterParams) {
        newCommand();
        return this.protocol.asyncDeleteDocs(filterParams);
    }

    public CompletableFuture<StatementExecuteOk> asyncCreateCollectionIndex(String schemaName, String collectionName, CreateIndexParams params) {
        newCommand();
        return this.protocol.asyncCreateCollectionIndex(schemaName, collectionName, params);
    }

    public CompletableFuture<StatementExecuteOk> asyncDropCollectionIndex(String schemaName, String collectionName, String indexName) {
        newCommand();
        return this.protocol.asyncDropCollectionIndex(schemaName, collectionName, indexName);
    }

    @Override
    public int getServerVariable(String variableName, int fallbackValue) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getServerDefaultCollationIndex() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setServerDefaultCollationIndex(int serverDefaultCollationIndex) {
        // TODO Auto-generated method stub

    }

    @Override
    public Log getLog() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setLog(Log log) {
        // TODO Auto-generated method stub

    }

    @Override
    public void configureTimezone() {
        // TODO Auto-generated method stub

    }

    @Override
    public TimeZone getDefaultTimeZone() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getErrorMessageEncoding() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setErrorMessageEncoding(String errorMessageEncoding) {
        // TODO Auto-generated method stub

    }

    @Override
    public int getMaxBytesPerChar(String javaCharsetName) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxBytesPerChar(Integer charsetIndex, String javaCharsetName) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public String getEncodingForIndex(int collationIndex) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ProfilerEventHandler getProfilerEventHandler() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setProfilerEventHandler(ProfilerEventHandler h) {
        // TODO Auto-generated method stub

    }

    public ServerSession getServerSession() {
        // TODO;
        return null;
    }

    @Override
    public boolean isSSLEstablished() {
        // TODO;
        return false;
    }
}
