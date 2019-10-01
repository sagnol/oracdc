/**
 * Copyright (c) 2018-present, http://a2-solutions.eu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package eu.solutions.a2.cdc.oracle.standalone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import eu.solutions.a2.cdc.oracle.HikariPoolConnectionFactory;
import eu.solutions.a2.cdc.oracle.OraPoolConnectionFactory;
import eu.solutions.a2.cdc.oracle.standalone.avro.AvroSchema;
import eu.solutions.a2.cdc.oracle.standalone.avro.Envelope;
import eu.solutions.a2.cdc.oracle.standalone.avro.Payload;
import eu.solutions.a2.cdc.oracle.standalone.avro.Source;
import eu.solutions.a2.cdc.oracle.utils.ExceptionUtils;

public class OraTable implements Runnable {

	private static final Logger LOGGER = Logger.getLogger(OraTable.class);

	@SuppressWarnings("serial")
	private static final Map<Integer, String> MYSQL_MAPPING =
			Collections.unmodifiableMap(new HashMap<Integer, String>() {{
				put(Types.BOOLEAN, "tinyint");
				put(Types.TINYINT, "tinyint");
				put(Types.SMALLINT, "smallint");
				put(Types.INTEGER, "int");
				put(Types.BIGINT, "bigint");
				put(Types.FLOAT, "float");
				put(Types.DOUBLE, "double");
				put(Types.DATE, "datetime");
				put(Types.TIMESTAMP, "timestamp");
				put(Types.VARCHAR, "varchar(4002)");
				put(Types.BINARY, "varbinary(8002)");
			}});
	@SuppressWarnings("serial")
	private static final Map<Integer, String> POSTGRESQL_MAPPING =
			Collections.unmodifiableMap(new HashMap<Integer, String>() {{
				put(Types.BOOLEAN, "boolean");
				put(Types.TINYINT, "smallint");
				put(Types.SMALLINT, "smallint");
				put(Types.INTEGER, "integer");
				put(Types.BIGINT, "bigint");
				put(Types.FLOAT, "real");
				put(Types.DOUBLE, "double precision");
				put(Types.DATE, "timestamp");
				put(Types.TIMESTAMP, "timestamp");
				put(Types.VARCHAR, "text");
				put(Types.BINARY, "bytea");
			}});
	private static Map<Integer, String> dataTypes = null;

	private int batchSize;
	private SendMethodIntf sendMethod;
	private final String tableOwner;
	private final String masterTable;
	private String masterTableSelSql;
	private String snapshotLog;
	private String snapshotLogSelSql;
	private String snapshotLogDelSql;
	private final List<OraColumn> allColumns = new ArrayList<>();
	private final HashMap<String, OraColumn> pkColumns = new LinkedHashMap<>();
	private AvroSchema schema;
	private final SimpleDateFormat iso8601DateFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
	private final SimpleDateFormat iso8601TimestampFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
	private boolean ready4Ops = false;

	private String sinkInsertSql = null; 
	private String sinkUpdateSql = null; 
	private String sinkDeleteSql = null; 
	private PreparedStatement sinkInsert = null; 
	private PreparedStatement sinkUpdate = null; 
	private PreparedStatement sinkDelete = null; 

	public OraTable(ResultSet resultSet, final int batchSize, final SendMethodIntf sendMethod) throws SQLException {
		this.batchSize = batchSize;
		this.sendMethod = sendMethod;
		this.tableOwner = resultSet.getString("LOG_OWNER");

		this.masterTable = resultSet.getString("MASTER");

		this.snapshotLog = resultSet.getString("LOG_TABLE");
		final String snapshotFqn = "\"" + this.tableOwner + "\"" + ".\"" + this.snapshotLog + "\"";
		this.snapshotLogDelSql = "delete from " + snapshotFqn + " where ROWID=?";

		Connection connection = resultSet.getStatement().getConnection();
		/*
select C.COLUMN_NAME, C.DATA_TYPE, C.DATA_LENGTH, C.DATA_PRECISION, C.DATA_SCALE, C.NULLABLE,
 (select 'Y' from ALL_IND_COLUMNS I
  where I.TABLE_OWNER=C.OWNER and I.TABLE_NAME=C.TABLE_NAME and I.INDEX_NAME='PK_DEPT' and C.COLUMN_NAME=I.COLUMN_NAME) PK
from   ALL_TAB_COLUMNS C
where  C.OWNER='SCOTT' and C.TABLE_NAME='DEPT'
  and  (C.DATA_TYPE in ('DATE', 'FLOAT', 'NUMBER', 'RAW', 'CHAR', 'NCHAR', 'VARCHAR2', 'NVARCHAR2', 'BLOB', 'CLOB') or C.DATA_TYPE like 'TIMESTAMP%');
		 */
		PreparedStatement statement = connection.prepareStatement(
				"select C.COLUMN_NAME, C.DATA_TYPE, C.DATA_LENGTH, C.DATA_PRECISION, C.DATA_SCALE, C.NULLABLE,\n" +
				"(select 'Y' from ALL_IND_COLUMNS I\n" +
				"  where I.TABLE_OWNER=C.OWNER and I.TABLE_NAME=C.TABLE_NAME and I.INDEX_NAME=? and C.COLUMN_NAME=I.COLUMN_NAME) PK\n" +
				"from   ALL_TAB_COLUMNS C\n" +
				"where  C.OWNER=? and C.TABLE_NAME=?\n" +
				"  and  (C.DATA_TYPE in ('DATE', 'FLOAT', 'NUMBER', 'RAW', 'CHAR', 'NCHAR', 'VARCHAR2', 'NVARCHAR2', 'BLOB', 'CLOB') or C.DATA_TYPE like 'TIMESTAMP%')");
		statement.setString(1, resultSet.getString("CONSTRAINT_NAME"));
		statement.setString(2, this.tableOwner);
		statement.setString(3, this.masterTable);
		ResultSet rsColumns = statement.executeQuery();
		StringBuilder masterSelect = new StringBuilder(512);
		boolean masterFirstColumn = true;
		masterSelect.append("select ");
		StringBuilder masterWhere = new StringBuilder(128);
		StringBuilder mViewSelect = new StringBuilder(128);
		boolean mViewFirstColumn = true;
		mViewSelect.append("select ");
		// Schema
		final String tableNameWithOwner = this.tableOwner + "." + this.masterTable;
		final AvroSchema schemaBefore = AvroSchema.STRUCT_OPTIONAL();
		schemaBefore.setName(tableNameWithOwner + ".PK");
		schemaBefore.setField("before");
		schemaBefore.initFields();
		final AvroSchema schemaAfter = AvroSchema.STRUCT_OPTIONAL();
		schemaAfter.setName(tableNameWithOwner + ".Data");
		schemaAfter.setField("after");
		schemaAfter.initFields();

		while (rsColumns .next()) {
			OraColumn column = new OraColumn(rsColumns);
			allColumns.add(column);
			schemaAfter.getFields().add(column.getAvroSchema());

			if (masterFirstColumn) {
				masterFirstColumn = false;
			} else {
				masterSelect.append(", ");
			}
			masterSelect.append("\"");
			masterSelect.append(column.getColumnName());
			masterSelect.append("\"");

			if (column.isPartOfPk()) {
				pkColumns.put(column.getColumnName(), column);
				schemaBefore.getFields().add(column.getAvroSchema());
				if (mViewFirstColumn) {
					mViewFirstColumn = false;
				} else {
					mViewSelect.append(", ");
					masterWhere.append(" and ");
				}
				mViewSelect.append("\"");
				mViewSelect.append(column.getColumnName());
				mViewSelect.append("\"");
				masterWhere.append("\"");
				masterWhere.append(column.getColumnName());
				masterWhere.append("\"=?");
			}
		}
		rsColumns.close();
		rsColumns = null;
		statement.close();
		statement = null;
		// Schema
		final AvroSchema op = AvroSchema.STRING_MANDATORY();
		op.setField("op");
		final AvroSchema ts_ms = AvroSchema.INT64_MANDATORY();
		ts_ms.setField("ts_ms");
		schema = AvroSchema.STRUCT_MANDATORY();
		schema.setName(tableNameWithOwner + ".Envelope");
		schema.initFields();
		schema.getFields().add(schemaBefore);
		schema.getFields().add(schemaAfter);
		schema.getFields().add(Source.schema());
		schema.getFields().add(op);
		schema.getFields().add(ts_ms);

		masterSelect.append(", ORA_ROWSCN, SYSTIMESTAMP at time zone 'GMT' as TIMESTAMP$$ from ");
		masterSelect.append("\"");
		masterSelect.append(this.tableOwner);
		masterSelect.append("\".\"");
		masterSelect.append(this.masterTable);
		masterSelect.append("\" where ");
		masterSelect.append(masterWhere);
		
		this.masterTableSelSql = masterSelect.toString();

		mViewSelect.append(", SEQUENCE$$, case DMLTYPE$$ when 'I' then 'c' when 'U' then 'u' else 'd' end as OPTYPE$$, ORA_ROWSCN, SYSTIMESTAMP at time zone 'GMT' as TIMESTAMP$$, ROWID from ");
		mViewSelect.append(snapshotFqn);
		mViewSelect.append(" order by SEQUENCE$$");
		this.snapshotLogSelSql = mViewSelect.toString();
	}

	public OraTable(final Source source, final AvroSchema tableSchema, boolean autoCreateTable) {
		this.tableOwner = source.getOwner();
		this.masterTable = source.getTable();
		int pkColCount = 0;
		for (AvroSchema columnSchema : tableSchema.getFields().get(0).getFields()) {
			final OraColumn column = new OraColumn(columnSchema, true);
			pkColumns.put(column.getColumnName(), column);
			pkColCount++;
		}
		// Only non PK columns!!!
		for (AvroSchema columnSchema : tableSchema.getFields().get(1).getFields()) {
			if (!pkColumns.containsKey(columnSchema.getField())) {
				final OraColumn column = new OraColumn(columnSchema, false);
				allColumns.add(column);
			}
		}
		// Prepare UPDATE/INSERT/DELETE statements...
		final StringBuilder sbDelUpdWhere = new StringBuilder(128);
		sbDelUpdWhere.append(" where ");

		final StringBuilder sbInsSql = new StringBuilder(256);
		sbInsSql.append("insert into ");
		sbInsSql.append(this.masterTable);
		sbInsSql.append("(");
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int pkColumnNo = 0;
		while (iterator.hasNext()) {
			final String columnName = iterator.next().getValue().getColumnName();

			if (pkColumnNo > 0) {
				sbDelUpdWhere.append(" and ");
			}
			sbDelUpdWhere.append(columnName);
			sbDelUpdWhere.append("=?");

			sbInsSql.append(columnName);
			if (pkColumnNo < pkColCount - 1) {
				sbInsSql.append(",");
			}
			pkColumnNo++;
		}

		final StringBuilder sbUpdSql = new StringBuilder(256);
		sbUpdSql.append("update ");
		sbUpdSql.append(this.masterTable);
		sbUpdSql.append(" set ");
		final int nonPkColumnCount = allColumns.size();
		for (int i = 0; i < nonPkColumnCount; i++) {
			sbInsSql.append(",");
			sbInsSql.append(allColumns.get(i).getColumnName());

			sbUpdSql.append(allColumns.get(i).getColumnName());
			if (i < nonPkColumnCount - 1) {
				sbUpdSql.append("=?,");
			} else {
				sbUpdSql.append("=?");
			}
		}
		sbInsSql.append(") values(");
		final int totalColumns = nonPkColumnCount + pkColCount;
		for (int i = 0; i < totalColumns; i++) {
			if (i < totalColumns - 1) {
				sbInsSql.append("?,");
			} else {
				sbInsSql.append("?)");
			}
		}

		final StringBuilder sbDelSql = new StringBuilder(128);
		sbDelSql.append("delete from ");
		sbDelSql.append(this.masterTable);
		sbDelSql.append(sbDelUpdWhere);

		sbUpdSql.append(sbDelUpdWhere);

		// Check for table existence
		try (Connection connection = HikariPoolConnectionFactory.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			ResultSet resultSet = metaData.getTables(null, null, masterTable, null);
			if (resultSet.next()) {
				ready4Ops = true;
			}
			resultSet.close();
			resultSet = null;
		} catch (SQLException sqle) {
			ready4Ops = false;
			LOGGER.error(ExceptionUtils.getExceptionStackTrace(sqle));
		}
		if (!ready4Ops && autoCreateTable) {
			// Create table in target database
			try (Connection connection = HikariPoolConnectionFactory.getConnection()) {
				Statement statement = connection.createStatement();
				statement.executeUpdate(createTableSql());
				ready4Ops = true;
			} catch (SQLException sqle) {
				ready4Ops = false;
				LOGGER.error(ExceptionUtils.getExceptionStackTrace(sqle));
			}
		}

		sinkInsertSql = sbInsSql.toString(); 
		sinkUpdateSql = sbUpdSql.toString(); 
		sinkDeleteSql = sbDelSql.toString(); 
	}

	private String createTableSql() {
		final StringBuilder sbCreateTable = new StringBuilder(256);
		final StringBuilder sbPrimaryKey = new StringBuilder(64);

		if (dataTypes == null) {
			if (HikariPoolConnectionFactory.getDbType() == HikariPoolConnectionFactory.DB_TYPE_POSTGRESQL) {
				dataTypes = POSTGRESQL_MAPPING;
			} else {
				//TODO - more types required
				dataTypes = MYSQL_MAPPING;
			}
		}

		sbCreateTable.append("create table ");
		sbCreateTable.append(this.masterTable);
		sbCreateTable.append("(\n");

		sbPrimaryKey.append(",\nconstraint ");
		sbPrimaryKey.append(this.masterTable);
		sbPrimaryKey.append("_PK primary key(");
		
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		while (iterator.hasNext()) {
			OraColumn column = iterator.next().getValue();
			sbCreateTable.append("  ");
			sbCreateTable.append(column.getColumnName());
			sbCreateTable.append(" ");
			sbCreateTable.append(dataTypes.get(column.getJdbcType()));
			sbCreateTable.append(" not null");

			sbPrimaryKey.append(column.getColumnName());

			if (iterator.hasNext()) {
				sbCreateTable.append(",\n");
				sbPrimaryKey.append(",");
			}
		}
		sbPrimaryKey.append(")");

		final int nonPkColumnCount = allColumns.size();
		for (int i = 0; i < nonPkColumnCount; i++) {
			OraColumn column = allColumns.get(i);
			sbCreateTable.append(",\n  ");
			sbCreateTable.append(column.getColumnName());
			sbCreateTable.append(" ");
			sbCreateTable.append(dataTypes.get(column.getJdbcType()));
			if (!column.isNullable()) {
				sbCreateTable.append(" not null");
			}
		}

		sbCreateTable.append(sbPrimaryKey);
		sbCreateTable.append("\n)");

		return sbCreateTable.toString();
	}

	public void run() {
		// Poll data (standalone mode)
		try (Connection connection = OraPoolConnectionFactory.getConnection();
				PreparedStatement stmtLog = connection.prepareStatement(snapshotLogSelSql);
				PreparedStatement stmtMaster = connection.prepareStatement(masterTableSelSql);
				PreparedStatement stmtDeleteLog = connection.prepareStatement(snapshotLogDelSql)) {
			final List<RowId> logRows2Delete = new ArrayList<>();
			//TODO - SEQUENCE$$ for start!!!
			// Read materialized view log and get PK values
			ResultSet rsLog = stmtLog.executeQuery();
			int recordCount = 0;
			while (rsLog.next() && recordCount < batchSize) {
				recordCount++;
				final String opType = rsLog.getString("OPTYPE$$");
				final Map<String, Object> columnValues = new LinkedHashMap<>();
				final boolean deleteOp = "d".equals(opType);
				// process primary key information from materialized view log
				processPkColumns(deleteOp, rsLog, columnValues, stmtMaster);
				// Add ROWID to list for delete after sending data to queue
				logRows2Delete.add(rsLog.getRowId("ROWID"));

				boolean success = true;
				final Envelope envelope = new Envelope(
						this.getSchema(),
						new Payload(new Source(tableOwner, masterTable), opType));

				if (deleteOp) {
					// For DELETE we have only "before" data
					envelope.getPayload().setBefore(columnValues);
					// For delete we need to get TS & SCN from snapshot log
					envelope.getPayload().getSource().setTs_ms(
							rsLog.getTimestamp("TIMESTAMP$$").getTime());
					envelope.getPayload().getSource().setScn(
							rsLog.getBigDecimal("ORA_ROWSCN").toBigInteger());					
				} else {
					// Get data from master table
					ResultSet rsMaster = stmtMaster.executeQuery();
					// We're working with PK
					if (rsMaster.next()) {
						processAllColumns(rsMaster, columnValues);
						// For INSERT/UPDATE  we have only "after" data 
						envelope.getPayload().setAfter(columnValues);
						// For delete we need to get TS & SCN from snapshot log
						envelope.getPayload().getSource().setTs_ms(
								rsMaster.getTimestamp("TIMESTAMP$$").getTime());
						envelope.getPayload().getSource().setScn(
								rsMaster.getBigDecimal("ORA_ROWSCN").toBigInteger());					
					} else {
						success = false;
						LOGGER.error("Primary key = " + nonExistentPk(rsLog) + " not found in " + tableOwner + "." + masterTable);
						LOGGER.error("\twhile executing\n\t\t" + masterTableSelSql);
					}
					// Close unneeded ResultSet
					rsMaster.close();
					rsMaster = null;
				}
				// Ready to process message
				if (success) {
					final StringBuilder messageKey = new StringBuilder(64);
					messageKey.append(tableOwner);
					messageKey.append(".");
					messageKey.append(masterTable);
					messageKey.append("-");
					messageKey.append(rsLog.getLong("SEQUENCE$$"));
					sendMethod.sendData(messageKey.toString(), envelope);
				}
			}
			rsLog.close();
			rsLog = null;
			// Perform deletion
			//TODO - success check of send!!!
			for (RowId rowId : logRows2Delete) {
				stmtDeleteLog.setRowId(1, rowId);
				stmtDeleteLog.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error(ExceptionUtils.getExceptionStackTrace(e));
		}
	}

	public AvroSchema getSchema() {
		return schema;
	}

	public String getMasterTable() {
		return masterTable;
	}

	public void putData(Connection connection, Payload payload) throws SQLException {
		switch (payload.getOp()) {
		case "c":
			processInsert(connection, payload.getAfter());
			break;
		case "u":
			processUpdate(connection, payload.getAfter());
			break;
		case "d":
			processDelete(connection, payload.getBefore());
			break;
		}
	}


	public void closeCursors() throws SQLException {
		if (sinkInsert != null) {
			sinkInsert.close();
			sinkInsert = null;
		}
		if (sinkUpdate != null) {
			sinkUpdate.close();
			sinkUpdate = null;
		}
		if (sinkDelete != null) {
			sinkDelete.close();
			sinkDelete = null;
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(128);
		if (this.snapshotLog != null) {
			sb.append("\"");
			sb.append(this.tableOwner);
			sb.append("\".\"");
			sb.append(this.snapshotLog);
			sb.append("\" on ");
		}
		sb.append("\"");
		sb.append(this.tableOwner);
		sb.append("\".\"");
		sb.append(this.masterTable);
		sb.append("\"");
		return sb.toString();
	}

	private void processPkColumns(final boolean deleteOp, ResultSet rsLog,
			final Map<String, Object> columnValues, PreparedStatement stmtMaster) throws SQLException {
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int bindNo = 1;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			final String columnName = oraColumn.getColumnName();
			switch (oraColumn.getJdbcType()) {
			case Types.DATE:
				//TODO Timezone support!!!!
				columnValues.put(columnName, rsLog.getDate(columnName).getTime());
				if (!deleteOp)
					stmtMaster.setDate(bindNo, rsLog.getDate(columnName));
				break;
			case Types.TINYINT:
				columnValues.put(columnName, rsLog.getByte(columnName));
				if (!deleteOp)
					stmtMaster.setByte(bindNo, rsLog.getByte(columnName));
				break;
			case Types.SMALLINT:
				columnValues.put(columnName, rsLog.getShort(columnName));
				if (!deleteOp)
					stmtMaster.setShort(bindNo, rsLog.getShort(columnName));
				break;
			case Types.INTEGER:
				columnValues.put(columnName, rsLog.getInt(columnName));
				if (!deleteOp)
					stmtMaster.setInt(bindNo, rsLog.getInt(columnName));
				break;
			case Types.BIGINT:
				columnValues.put(columnName, rsLog.getLong(columnName));
				if (!deleteOp)
					stmtMaster.setLong(bindNo, rsLog.getLong(columnName));
				break;
			case Types.BINARY:
				columnValues.put(columnName, rsLog.getBytes(columnName));
				if (!deleteOp)
					stmtMaster.setBytes(bindNo, rsLog.getBytes(columnName));
				break;
			case Types.CHAR:
			case Types.VARCHAR:
				columnValues.put(columnName, rsLog.getString(columnName));
				if (!deleteOp)
					stmtMaster.setString(bindNo, rsLog.getString(columnName));
				break;
			case Types.NCHAR:
			case Types.NVARCHAR:
				columnValues.put(columnName, rsLog.getNString(columnName));
				if (!deleteOp)
					stmtMaster.setNString(bindNo, rsLog.getNString(columnName));
				break;
			case Types.TIMESTAMP:
				//TODO Timezone support!!!!
				columnValues.put(columnName, rsLog.getTimestamp(columnName).getTime());
				if (!deleteOp)
					stmtMaster.setTimestamp(bindNo, rsLog.getTimestamp(columnName));
				break;
			default:
				// Types.FLOAT, Types.DOUBLE, Types.BLOB, Types.CLOB 
				// TODO - is it possible?
				columnValues.put(columnName, rsLog.getString(columnName));
				if (!deleteOp)
					stmtMaster.setString(bindNo, rsLog.getString(columnName));
				break;
			}
			bindNo++;
		}
	}

	private void processAllColumns(
			ResultSet rsMaster, final Map<String, Object> columnValues) throws SQLException {
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			final String columnName = oraColumn.getColumnName();
			if (!pkColumns.containsKey(columnName)) {
				// Don't process PK value again
				switch (oraColumn.getJdbcType()) {
				case Types.DATE:
					//TODO Timezone support!!!!
					final long dateColumnValue = rsMaster.getDate(columnName).getTime();
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, dateColumnValue);
					break;
				case Types.TINYINT:
					final byte byteColumnValue = rsMaster.getByte(columnName);
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, byteColumnValue);									
					break;
				case Types.SMALLINT:
					final short shortColumnValue = rsMaster.getShort(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, shortColumnValue);
					break;
				case Types.INTEGER:
					final int intColumnValue = rsMaster.getInt(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, intColumnValue);
					break;
				case Types.BIGINT:
					final long longColumnValue = rsMaster.getLong(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, longColumnValue);
					break;
				case Types.BINARY:
					final byte[] binaryColumnValue = rsMaster.getBytes(columnName);
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, binaryColumnValue);
					break;
				case Types.CHAR:
				case Types.VARCHAR:
					final String charColumnValue = rsMaster.getString(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, charColumnValue);
					break;
				case Types.NCHAR:
				case Types.NVARCHAR:
					final String nCharColumnValue = rsMaster.getNString(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, nCharColumnValue);
					break;
				case Types.TIMESTAMP:
					//TODO Timezone support!!!!
					final long tsColumnValue = rsMaster.getTimestamp(columnName).getTime();
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, tsColumnValue);
					break;
				case Types.FLOAT:
					final float floatColumnValue = rsMaster.getFloat(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, floatColumnValue);
					break;
				case Types.DOUBLE:
					final double doubleColumnValue = rsMaster.getDouble(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, doubleColumnValue);
					break;
				case Types.BLOB:
					final Blob blobColumnValue = rsMaster.getBlob(columnName);
					if (rsMaster.wasNull() || blobColumnValue.length() < 1) {
						columnValues.put(columnName, null);
					} else {
						try (InputStream is = blobColumnValue.getBinaryStream();
							ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
							final byte[] data = new byte[16384];
							int bytesRead;
							while ((bytesRead = is.read(data, 0, data.length)) != -1) {
								baos.write(data, 0, bytesRead);
							}
							columnValues.put(columnName, baos.toByteArray());
						} catch (IOException ioe) {
							LOGGER.error("IO Error while processing BLOB column " + 
									 tableOwner + "." + masterTable + "(" + columnName + ")");
							LOGGER.error("\twhile executing\n\t\t" + masterTableSelSql);
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
						}
					}
					break;
				case Types.CLOB:
					final Clob clobColumnValue = rsMaster.getClob(columnName);
					if (rsMaster.wasNull() || clobColumnValue.length() < 1) {
						columnValues.put(columnName, null);
					} else {
						try (Reader reader = clobColumnValue.getCharacterStream()) {
							final char[] data = new char[8192];
							StringBuilder sbClob = new StringBuilder(8192);
							int charsRead;
							while ((charsRead = reader.read(data, 0, data.length)) != -1) {
								sbClob.append(data, 0, charsRead);
							}
							columnValues.put(columnName, sbClob.toString());
						} catch (IOException ioe) {
							LOGGER.error("IO Error while processing CLOB column " + 
									tableOwner + "." + masterTable + "(" + columnName + ")");
							LOGGER.error("\twhile executing\n\t\t" + masterTableSelSql);
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
						}
					}
					break;
				default:
					break;
				}
			}
		}
	}

	private String nonExistentPk(ResultSet rsLog) throws SQLException {
		StringBuilder sbPrimaryKey = new StringBuilder(128);
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int i = 0;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			final String columnName = oraColumn.getColumnName();
			if (i > 0)
				sbPrimaryKey.append(" and ");
			sbPrimaryKey.append(columnName);
			sbPrimaryKey.append("=");
			switch (oraColumn.getJdbcType()) {
			case Types.DATE:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(
						iso8601DateFmt.format(new Date(rsLog.getDate(columnName).getTime())));
				sbPrimaryKey.append("'");
				break;
			case Types.TINYINT:
				sbPrimaryKey.append(rsLog.getByte(columnName));
				break;
			case Types.SMALLINT:
				sbPrimaryKey.append(rsLog.getShort(columnName));
				break;
			case Types.INTEGER:
				sbPrimaryKey.append(rsLog.getInt(columnName));
				break;
			case Types.BIGINT:
				sbPrimaryKey.append(rsLog.getLong(columnName));
				break;
			case Types.BINARY:
				// Encode binary to Base64
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(
						Base64.getEncoder().encodeToString(rsLog.getBytes(columnName)));
				sbPrimaryKey.append("'");
				break;
			case Types.CHAR:
			case Types.VARCHAR:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(rsLog.getString(columnName));
				sbPrimaryKey.append("'");
				break;
			case Types.NCHAR:
			case Types.NVARCHAR:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(rsLog.getNString(columnName));
				sbPrimaryKey.append("'");
				break;
			case Types.TIMESTAMP:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(
						iso8601TimestampFmt.format(new Date(rsLog.getTimestamp(columnName).getTime())));
				sbPrimaryKey.append("'");
				break;
			default:
				// Types.FLOAT, Types.DOUBLE, Types.BLOB, Types.CLOB
				// TODO - is it possible?
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(rsLog.getString(columnName));
				sbPrimaryKey.append("'");
				break;
			}
			i++;
		}
		return sbPrimaryKey.toString();
	}

	private void processInsert(
			final Connection connection, final Map<String, Object> data) throws SQLException {
		if (sinkInsert == null) {
			sinkInsert = connection.prepareStatement(sinkInsertSql);
		}
		int columnNo = 1;
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			oraColumn.bindWithPrepStmt(sinkInsert, columnNo, data);
			columnNo++;
		}
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			oraColumn.bindWithPrepStmt(sinkInsert, columnNo, data);
			columnNo++;
		}
		sinkInsert.executeUpdate();
	}

	private void processUpdate(
			final Connection connection, final Map<String, Object> data) throws SQLException {
		if (sinkUpdate == null) {
			sinkUpdate = connection.prepareStatement(sinkUpdateSql);
		}
		int columnNo = 1;
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			oraColumn.bindWithPrepStmt(sinkUpdate, columnNo, data);
			columnNo++;
		}
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			oraColumn.bindWithPrepStmt(sinkUpdate, columnNo, data);
			columnNo++;
		}
		final int recordCount = sinkUpdate.executeUpdate();
		if (recordCount == 0) {
			LOGGER.warn("Primary key not found, executing insert");
			processInsert(connection, data);
		}
	}

	private void processDelete(
			final Connection connection, final Map<String, Object> data) throws SQLException {
		if (sinkDelete == null) {
			sinkDelete = connection.prepareStatement(sinkDeleteSql);
		}
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int columnNo = 1;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			oraColumn.bindWithPrepStmt(sinkDelete, columnNo, data);
			columnNo++;
		}
		sinkDelete.executeUpdate();
	}

}
