/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.connect.jdbc.util;

import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Utilties for interacting with a JDBC database.
 */
public class JdbcUtils {

  private static final Logger log = LoggerFactory.getLogger(JdbcUtils.class);

  /**
   * The default table types to include when listing tables if none are specified. Valid values
   * are those specified by the @{java.sql.DatabaseMetaData#getTables} method's TABLE_TYPE column.
   * The default only includes standard, user-defined tables.
   */
  public static final Set<String> DEFAULT_TABLE_TYPES = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("TABLE"))
  );

  private static final int GET_TABLES_TYPE_COLUMN = 4;
  private static final int GET_TABLES_NAME_COLUMN = 3;

  private static final int GET_COLUMNS_COLUMN_NAME = 4;
  private static final int GET_COLUMNS_IS_NULLABLE = 18;
  private static final int GET_COLUMNS_IS_AUTOINCREMENT = 23;


  private static final ThreadLocal<SimpleDateFormat> DATE_FORMATTER = new ThreadLocal<SimpleDateFormat>() {
    @Override
    protected SimpleDateFormat initialValue() {
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
      sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
      return sdf;
    }
  };

  /**
   * Get a list of tables in the database. This uses the default filters, which only include
   * user-defined tables.
   * @param conn database connection
   * @return a list of tables
   * @throws SQLException
   */
  public static List<String> getTables(Connection conn, String schemaPattern) throws SQLException {
    return getTables(conn, schemaPattern, DEFAULT_TABLE_TYPES);
  }

  /**
   * Get a list of table names in the database.
   * @param conn database connection
   * @param types a set of table types that should be included in the results
   * @throws SQLException
   */
  public static List<String> getTables(Connection conn, String schemaPattern, Set<String> types) throws SQLException {
    DatabaseMetaData metadata = conn.getMetaData();
    try (ResultSet rs = metadata.getTables(null, schemaPattern, "%", null)) {
      List<String> tableNames = new ArrayList<>();
      while (rs.next()) {
        if (types.contains(rs.getString(GET_TABLES_TYPE_COLUMN))) {
          String colName = rs.getString(GET_TABLES_NAME_COLUMN);
          // SQLite JDBC driver does not correctly mark these as system tables
          if (metadata.getDatabaseProductName().equals("SQLite") && colName.startsWith("sqlite_")) {
            continue;
          }

          tableNames.add(colName);
        }
      }
      return tableNames;
    }
  }

  /**
   * Look up the autoincrement column for the specified table.
   * @param conn database connection
   * @param table the table to
   * @return the name of the column that is an autoincrement column, or null if there is no
   *         autoincrement column or more than one exists
   * @throws SQLException
   */
  public static String getAutoincrementColumn(Connection conn, String schemaPattern, String table) throws SQLException {
    String result = null;
    int matches = 0;

    try (ResultSet rs = conn.getMetaData().getColumns(null, schemaPattern, table, "%")) {
      // Some database drivers (SQLite) don't include all the columns
      if (rs.getMetaData().getColumnCount() >= GET_COLUMNS_IS_AUTOINCREMENT) {
        while (rs.next()) {
          if (rs.getString(GET_COLUMNS_IS_AUTOINCREMENT).equals("YES")) {
            result = rs.getString(GET_COLUMNS_COLUMN_NAME);
            matches++;
          }
        }
        return (matches == 1 ? result : null);
      }
    }

    // Fallback approach is to query for a single row. This unfortunately does not work with any
    // empty table
    log.trace("Falling back to SELECT detection of auto-increment column for {}:{}", conn, table);
    try (Statement stmt = conn.createStatement()) {
      String quoteString = getIdentifierQuoteString(conn);
      ResultSet rs = stmt.executeQuery("SELECT * FROM " + quoteString + table + quoteString + " LIMIT 1");
      ResultSetMetaData rsmd = rs.getMetaData();
      for (int i = 1; i < rsmd.getColumnCount(); i++) {
        if (rsmd.isAutoIncrement(i)) {
          result = rsmd.getColumnName(i);
          matches++;
        }
      }
    }
    return (matches == 1 ? result : null);
  }

  public static boolean isColumnNullable(Connection conn, String schemaPattern, String table, String column)
      throws SQLException {
    try (ResultSet rs = conn.getMetaData().getColumns(null, schemaPattern, table, column)) {
      if (rs.getMetaData().getColumnCount() > GET_COLUMNS_IS_NULLABLE) {
        // Should only be one match
        if (!rs.next()) {
          return false;
        }
        return rs.getString(GET_COLUMNS_IS_NULLABLE).equals("YES");
      }
    }

    return false;
  }

  /**
   * Format the given Date assuming UTC timezone in a format supported by SQL.
   * @param date the date to convert to a String
   * @return the formatted string
   */
  public static String formatUTC(Date date) {
    return DATE_FORMATTER.get().format(date);
  }

  /**
   * Get the string used for quoting identifiers in this database's SQL dialect.
   * @param connection the database connection
   * @return the quote string
   * @throws SQLException
   */
  public static String getIdentifierQuoteString(Connection connection) throws SQLException {
    String quoteString = connection.getMetaData().getIdentifierQuoteString();
    quoteString = quoteString == null ? "" : quoteString;
    return quoteString;
  }

  /**
   * Quote the given string.
   * @param orig the string to quote
   * @param quote the quote character
   * @return the quoted string
   */
  public static String quoteString(String orig, String quote) {
    return quote + orig + quote;
  }

  public static String quoteString(String origPrefix, String orig, String quote) {
    if (origPrefix != null && !origPrefix.isEmpty()) {
      return quote + origPrefix + quote + '.' + quoteString(orig, quote);
    }
    return quoteString(orig, quote);
  }
  /**
   * Return current time at the database
   * @param conn
   * @param cal
   * @return
   */
  public static Timestamp getCurrentTimeOnDB(Connection conn, Calendar cal) throws SQLException, ConnectException {
    String query;

    // This is ugly, but to run a function, everyone does 'select function()'
    // except Oracle that does 'select function() from dual'
    // and Derby uses either the dummy table SYSIBM.SYSDUMMY1  or values expression (I chose to use values)
    String dbProduct = conn.getMetaData().getDatabaseProductName();
    if ("Oracle".equals(dbProduct))
      query = "select CURRENT_TIMESTAMP from dual";
    else if ("Apache Derby".equals(dbProduct))
      query = "values(CURRENT_TIMESTAMP)";
    else
      query = "select CURRENT_TIMESTAMP;";

    try (Statement stmt = conn.createStatement()) {
      log.debug("executing query " + query + " to get current time from database");
      ResultSet rs = stmt.executeQuery(query);
      if (rs.next())
        return rs.getTimestamp(1, cal);
      else
        throw new ConnectException("Unable to get current time from DB using query " + query + " on database " + dbProduct);
    } catch (SQLException e) {
      log.error("Failed to get current time from DB using query " + query + " on database " + dbProduct, e);
      throw e;
    }
  }
}

