/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - Carlos Ruiz - globalqss                                           *
 * Sponsored by FH                                                     *
 **********************************************************************/

package org.globalqss.moveclient.process;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.db.CConnection;
import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Util;

public class MoveClient extends SvrProcess {

	// Process to move a client from a external database to current

	private String p_JDBC_URL; // JDBC URL of the external database
	private String p_UserName; // optional to connect to the JDBC URL, if empty use the same as target 
	private String p_Password; // optional to connect to the JDBC URL, if empty use the same as target
	private String p_TablesToExclude; // optional, comma separated list of tables to exclude
	private String p_ClientsToInclude; // optional, comma separated list, if empty then all clients >= 1000000 will be moved
	private String p_ClientsToExclude; // optional, comma separated list of clients to exclude
	private boolean p_IsValidateOnly; // to do just validation and not execute the process

	final static String insertConversionId = "INSERT INTO T_MoveClient (AD_PInstance_ID, TableName, Source_ID, Target_ID) VALUES (?, ?, ?, ?)";

	private Connection externalConn;
	private StringBuffer p_excludeTablesWhere = new StringBuffer();
	private StringBuffer p_whereClient = new StringBuffer();
	private List<String> p_errorList = new ArrayList<String>();
	private List<String> p_tablesVerified = new ArrayList<String>();
	private List<String> p_columnsVerified = new ArrayList<String>();
	private List<String> p_idSystemConversion = new ArrayList<String>(); // can consume lot of memory but it helps for performance

	@Override
	protected void prepare() {
		for (ProcessInfoParameter para : getParameter()) {
			String name = para.getParameterName();
			if ("MoveClient_JDBC_URL".equals(name)) {
				p_JDBC_URL  = para.getParameterAsString();
			} else if ("UserName".equals(name)) {
				p_UserName = para.getParameterAsString();
			} else if ("Password".equals(name)) {
				p_Password = para.getParameterAsString();
			} else if ("MoveClient_TablesToExclude".equals(name)) {
				p_TablesToExclude = para.getParameterAsString();
			} else if ("MoveClient_ClientsToInclude".equals(name)) {
				p_ClientsToInclude = para.getParameterAsString();
			} else if ("MoveClient_ClientsToExclude".equals(name)) {
				p_ClientsToExclude = para.getParameterAsString();
			} else if ("IsValidateOnly".equals(name)) {
				p_IsValidateOnly = para.getParameterAsBoolean();
			} else {
				log.log(Level.SEVERE, "Unknown Parameter: " + name);
			}
		}
	}

	@Override
	protected String doIt() throws Exception {
		// validate parameters
		if (Util.isEmpty(p_JDBC_URL, true))
			throw new AdempiereException("Fill mandatory JDBC_URL");
		if (! Util.isEmpty(p_ClientsToInclude, true) && ! Util.isEmpty(p_ClientsToExclude, true))
			throw new AdempiereException("Clients to exclude and include cannot be used at the same time");
		if (Util.isEmpty(p_UserName, true))
			p_UserName = CConnection.get().getDbUid();
		if (Util.isEmpty(p_Password, true))
			p_Password = CConnection.get().getDbPwd();

		// Construct the where clauses
		p_excludeTablesWhere.append("(UPPER(AD_Table.TableName) NOT LIKE 'T|_%' ESCAPE '|'"); // exclude temporary tables
		if (Util.isEmpty(p_TablesToExclude, true)) {
			p_excludeTablesWhere.append(")");
		} else {
			p_excludeTablesWhere.append(" AND UPPER(TableName) NOT IN (");
			boolean addComma = false;
			for (String tableName : p_TablesToExclude.split(",")) {
				if (addComma) {
					p_excludeTablesWhere.append(",");
				} else {
					addComma = true;
				}
				p_excludeTablesWhere.append(DB.TO_STRING(tableName.toUpperCase()));
			}
			p_excludeTablesWhere.append("))");
		}

		p_whereClient.append("AD_Client.AD_Client_ID NOT IN (0,11"); // by default exclude System and GardenAdmin
		if (! Util.isEmpty(p_ClientsToExclude, true)) {
			for (String clientStr : p_ClientsToExclude.split(",")) {
				p_whereClient.append(",");
				int clientInt;
				try {
					clientInt = Integer.parseInt(clientStr);
				} catch (NumberFormatException e) {
					throw new AdempiereException("Error in parameter Clients to Exclude, must be a list of integer separated by commas, wrong format: " + clientStr);
				}
				p_whereClient.append(clientInt);
			}
		}
		p_whereClient.append(")");
		if (! Util.isEmpty(p_ClientsToInclude, true)) {
			p_whereClient.append(" AND AD_Client.AD_Client_ID IN (");
			boolean addComma = false;
			for (String clientStr : p_ClientsToInclude.split(",")) {
				if (addComma) {
					p_whereClient.append(",");
				} else {
					addComma = true;
				}
				int clientInt;
				try {
					clientInt = Integer.parseInt(clientStr);
				} catch (NumberFormatException e) {
					throw new AdempiereException("Error in parameter Clients to Include, must be a list of integer separated by commas, wrong format: " + clientStr);
				}
				p_whereClient.append(clientInt);
			}
			p_whereClient.append(")");
		}

		// Make the connection to external database
		externalConn = null;
		try {
			try {
				externalConn = DB.getDatabase(p_JDBC_URL).getDriverConnection(p_JDBC_URL, p_UserName, p_Password);
			} catch (Exception e) {
				throw new AdempiereException("Could not get a connection to " + p_JDBC_URL + ",\nCause: " + e.getLocalizedMessage());
			}

			validate();
			if (p_errorList.size() > 0) {
				for (String err : p_errorList) {
					addLog(err);
				}
				return "@Error@";
			}

			if (! p_IsValidateOnly) {
				moveClient();
			}
		} finally {
			if (externalConn != null)
				externalConn.close();
		}

		return "@OK@";
	}

	private void validate() {
		// validate there are clients to move
		StringBuilder sqlValidClients = new StringBuilder()
				.append("SELECT COUNT(*) FROM AD_Client WHERE ")
				.append(p_whereClient);
		int cntVC = countInExternal(sqlValidClients.toString());
		if (cntVC == 0) {
			throw new AdempiereUserError("No clients to move");
		}

		// validate if there are attachments using external storage provider - inform not implemented yet (blocking)
		if (! p_excludeTablesWhere.toString().contains("'AD_ATTACHMENT'")) {
			statusUpdate("Checking storage for attachments");
			StringBuilder sqlExternalAttachment = new StringBuilder()
					.append("SELECT COUNT(*) FROM AD_Attachment")
					.append(" JOIN AD_Client ON (AD_Attachment.AD_Client_ID=AD_Client.AD_Client_ID)")
					.append(" JOIN AD_ClientInfo ON (AD_Attachment.AD_Client_ID=AD_ClientInfo.AD_Client_ID)")
					.append(" JOIN AD_Table ON (AD_Attachment.AD_Table_ID=AD_Table.AD_Table_ID)")
					.append(" LEFT JOIN AD_StorageProvider ON (AD_StorageProvider.AD_StorageProvider_ID=AD_ClientInfo.AD_StorageProvider_ID)")
					.append(" WHERE AD_StorageProvider.Method IS NOT NULL AND AD_StorageProvider.Method!='DB'")
					.append(" AND ").append(p_whereClient)
					.append(" AND ").append(p_excludeTablesWhere)
					;
			int cntES = countInExternal(sqlExternalAttachment.toString());
			if (cntES > 0) {
				throw new AdempiereUserError("There are attachments using external storage provider - that's not implemented yet");
			}
		}

		// validate if there are archives using external storage provider - inform not implemented yet (blocking)
		if (! p_excludeTablesWhere.toString().contains("'AD_ARCHIVE'")) {
			statusUpdate("Checking storage for archives");
			StringBuilder sqlExternalArchive = new StringBuilder()
					.append("SELECT COUNT(*) FROM AD_Archive")
					.append(" JOIN AD_Client ON (AD_Archive.AD_Client_ID=AD_Client.AD_Client_ID)")
					.append(" JOIN AD_ClientInfo ON (AD_Archive.AD_Client_ID=AD_ClientInfo.AD_Client_ID)")
					.append(" JOIN AD_Table ON (AD_Archive.AD_Table_ID=AD_Table.AD_Table_ID)")
					.append(" LEFT JOIN AD_StorageProvider ON (AD_StorageProvider.AD_StorageProvider_ID=AD_ClientInfo.StorageArchive_ID)")
					.append(" WHERE AD_StorageProvider.Method IS NOT NULL AND AD_StorageProvider.Method!='DB'")
					.append(" AND ").append(p_whereClient)
					.append(" AND ").append(p_excludeTablesWhere)
					;
			int cntEA = countInExternal(sqlExternalArchive.toString());
			if (cntEA > 0) {
				throw new AdempiereUserError("There are archives using external storage provider - that's not implemented yet");
			}
		}

		// create list of tables to ignore
		// validate tables
		// for each source table not excluded
		StringBuilder sqlTablesSB = new StringBuilder()
				.append("SELECT TableName FROM AD_Table WHERE IsActive='Y' AND IsView='N' AND ")
				.append(p_excludeTablesWhere)
				.append(" ORDER BY TableName");

		String sqlRemoteTables = DB.getDatabase().convertStatement(sqlTablesSB.toString());
		PreparedStatement stmtRT = null;
		ResultSet rsRT = null;
		try {
			stmtRT = externalConn.prepareStatement(sqlRemoteTables, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			rsRT = stmtRT.executeQuery();
			while (rsRT.next()) {
				String tableName = rsRT.getString(1);
				validateExternalTable(tableName);
			}
		} catch (SQLException e) {
			throw new AdempiereException("Could not execute external query: " + sqlRemoteTables + "\nCause = " + e.getLocalizedMessage());
		} finally {
			DB.close(rsRT, stmtRT);
		}

	}

	private void validateExternalTable(String tableName) {
		statusUpdate("Validating table " + tableName);
		// if table doesn't have client data (taking into account include/exclude) in the source DB
		// add to the list of tables to ignore
		// ignore and continue with next table
		if (! "AD_Client".equalsIgnoreCase(tableName)) {
			StringBuilder sqlCountData = new StringBuilder()
					.append("SELECT COUNT(*) FROM ").append(tableName);
			if ("AD_Attribute_Value".equalsIgnoreCase(tableName)) {
				sqlCountData.append(" JOIN AD_Attribute ON (AD_Attribute_Value.AD_Attribute_ID=AD_Attribute.AD_Attribute_ID)");
				sqlCountData.append(" JOIN AD_Client ON (AD_Attribute.AD_Client_ID=AD_Client.AD_Client_ID)");
			} else if ("AD_PInstance_Log".equalsIgnoreCase(tableName)) {
				sqlCountData.append(" JOIN AD_PInstance ON (AD_PInstance_Log.AD_PInstance_ID=AD_PInstance.AD_PInstance_ID)");
				sqlCountData.append(" JOIN AD_Client ON (AD_PInstance.AD_Client_ID=AD_Client.AD_Client_ID)");
			} else {
				sqlCountData.append(" JOIN AD_Client ON (").append(tableName).append(".AD_Client_ID=AD_Client.AD_Client_ID)");
			}
			sqlCountData.append(" WHERE ").append(p_whereClient);
			int cntCD = countInExternal(sqlCountData.toString());
			if (cntCD == 0) {
				if (log.isLoggable(Level.INFO)) log.info("Ignoring " + tableName + ", doesn't have client data");
				return;
			}
			if (cntCD > 0 && "AD_Attribute_Value".equalsIgnoreCase(tableName)) {
				throw new AdempiereUserError("Table " + tableName + " has data, migration not supported");
			}
		}

		// if table is not present in target
		// inform blocking as it has client data
		MTable localTable = MTable.get(getCtx(), tableName);
		if (localTable == null || localTable.getAD_Table_ID() <= 0) {
			p_errorList.add("Table " + tableName + " doesn't exist");
			return;
		}

		// for each source column
		final String sqlRemoteColumnsST = ""
				+ " SELECT AD_Column.ColumnName, AD_Column.AD_Reference_ID, AD_Column.FieldLength"
				+ " FROM AD_Column"
				+ " JOIN AD_Table ON (AD_Table.AD_Table_ID=AD_Column.AD_Table_ID)"
				+ " WHERE UPPER(AD_Table.TableName)=? AND AD_Column.IsActive='Y' AND AD_Column.ColumnSQL IS NULL"
				+ " ORDER BY AD_Column.ColumnName";
		String sqlRemoteColumns = DB.getDatabase().convertStatement(sqlRemoteColumnsST);
		PreparedStatement stmtRC = null;
		ResultSet rsRC = null;
		try {
			stmtRC = externalConn.prepareStatement(sqlRemoteColumns, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			stmtRC.setString(1, tableName.toUpperCase());
			rsRC = stmtRC.executeQuery();
			while (rsRC.next()) {
				String columnName = rsRC.getString(1);
				int refID = rsRC.getInt(2);
				int length = rsRC.getInt(3);
				if (columnName.equalsIgnoreCase("AD_Client_ID")) {
					p_columnsVerified.add(tableName.toUpperCase() + "." + columnName.toUpperCase());
				} else {
					validateExternalColumn(tableName, columnName, refID, length);
				}
			}
		} catch (SQLException e) {
			throw new AdempiereException("Could not execute external query: " + sqlRemoteColumns + "\nCause = " + e.getLocalizedMessage());
		} finally {
			DB.close(rsRC, stmtRC);
		}
		p_tablesVerified.add(tableName.toUpperCase());
	}

	private void validateExternalColumn(String tableName, String columnName, int refID, int length) {
		// inform if column is not present in target (blocking as it has client data)
		// statusUpdate("Validating column " + tableName + "." + columnName);
		MColumn localColumn = MColumn.get(getCtx(), tableName, columnName);
		if (localColumn == null || localColumn.getAD_Column_ID() <= 0) {
			p_errorList.add("Column " + tableName + "." + columnName +  " doesn't exist");
			return;
		}

		// inform if db type is different (blocking as it has client data)
		if (refID <= MTable.MAX_OFFICIAL_ID
				&& localColumn.getAD_Reference_ID() < MTable.MAX_OFFICIAL_ID 
				&& refID != localColumn.getAD_Reference_ID()) {
			p_errorList.add("Column " + tableName + "." + columnName +  " has different type in dictionary, external: " + refID + ", local: " + localColumn.getAD_Reference_ID());
		}

		// inform blocking if lengths are different
		if (length != localColumn.getFieldLength()) {
			p_errorList.add("Column " + tableName + "." + columnName +  " has different length in dictionary, external: " + length + ", local: " + localColumn.getFieldLength());
		}

		// when the column is a foreign key
		String foreignTable = localColumn.getReferenceTableName();
		if (foreignTable != null 
				&& (foreignTable.equalsIgnoreCase(tableName) || "AD_PInstance_Log".equalsIgnoreCase(tableName))) {
			foreignTable = "";
		}
		if (! Util.isEmpty(foreignTable)) {
			// verify all foreign keys pointing to a different client
			// if pointing to a different client non-system
			//   inform cross-client data corruption error
			// if pointing to system
			//   add to list of columns with system foreign keys
			//   inform if the system record is not in target database using uuid - blocking
			String uuidCol = MTable.getUUIDColumnName(foreignTable);
			StringBuilder sqlForeignClientSB = new StringBuilder();
			if ("AD_Ref_List".equalsIgnoreCase(foreignTable)) {
				sqlForeignClientSB
				.append("SELECT DISTINCT AD_Ref_List.AD_Client_ID, AD_Ref_List.AD_Ref_List_ID, AD_Ref_List.").append(uuidCol)
				.append(" FROM ").append(tableName);
				if (! "AD_Client".equalsIgnoreCase(tableName)) {
					sqlForeignClientSB.append(" JOIN AD_Client ON (").append(tableName).append(".AD_Client_ID=AD_Client.AD_Client_ID)");
				}
				sqlForeignClientSB.append(" JOIN AD_Ref_List ON (").append(tableName).append(".").append(columnName).append("=AD_Ref_List.");
				if ("AD_Ref_List_ID".equalsIgnoreCase(columnName)) {
					sqlForeignClientSB.append("AD_Ref_List_ID");
				} else {
					sqlForeignClientSB.append("Value");
				}
				sqlForeignClientSB.append(" AND AD_Ref_List.AD_Reference_ID=")
				.append(" (SELECT AD_Column.AD_Reference_Value_ID FROM AD_Column")
				.append(" JOIN AD_Table ON (AD_Column.AD_Table_ID=AD_Table.AD_Table_ID)")
				.append(" WHERE UPPER(AD_Table.TableName)='").append(tableName.toUpperCase())
				.append("' AND UPPER(AD_Column.ColumnName)='").append(columnName.toUpperCase()).append("'))")
				.append(" WHERE ").append(p_whereClient)
				.append(" AND ").append(foreignTable).append(".AD_Client_ID!=").append(tableName).append(".AD_Client_ID")
				.append(" ORDER BY 2");
			} else {
				sqlForeignClientSB
				.append("SELECT DISTINCT ").append(foreignTable).append(".AD_Client_ID, ")
				.append(foreignTable).append(".").append(foreignTable).append("_ID, ")
				.append(foreignTable).append(".").append(uuidCol)
				.append(" FROM ").append(tableName);
				if (! "AD_Client".equalsIgnoreCase(tableName)) {
					sqlForeignClientSB.append(" JOIN AD_Client ON (").append(tableName).append(".AD_Client_ID=AD_Client.AD_Client_ID)");
				}
				sqlForeignClientSB.append(" JOIN ").append(foreignTable)
				.append(" ON (").append(tableName).append(".").append(columnName).append("=").append(foreignTable).append(".");
				if ("AD_Language".equalsIgnoreCase(foreignTable) && !columnName.equalsIgnoreCase("AD_Language_ID")) {
					sqlForeignClientSB.append("AD_Language");
				} else if ("AD_EntityType".equalsIgnoreCase(foreignTable) && !columnName.equalsIgnoreCase("AD_EntityType_ID")) {
					sqlForeignClientSB.append("EntityType");
				} else {
					sqlForeignClientSB.append(foreignTable).append("_ID");
				}
				sqlForeignClientSB.append(")")
				.append(" WHERE ").append(p_whereClient)
				.append(" AND ").append(foreignTable).append(".AD_Client_ID!=").append(tableName).append(".AD_Client_ID")
				.append(" ORDER BY 2");
			}
			String sqlForeignClient = DB.getDatabase().convertStatement(sqlForeignClientSB.toString());
			PreparedStatement stmtFC = null;
			ResultSet rsFC = null;
			try {
				stmtFC = externalConn.prepareStatement(sqlForeignClient, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
				rsFC = stmtFC.executeQuery();
				while (rsFC.next()) {
					int clientID = rsFC.getInt(1);
					int foreignID = rsFC.getInt(2);
					String foreignUU = rsFC.getString(3);
					if (clientID > 0) {
						p_errorList.add("Column " + tableName + "." + columnName +  " has invalid cross-client reference to client " + clientID + " on ID=" + foreignID);
						continue;
					}
					if (foreignID > MTable.MAX_OFFICIAL_ID) {
						if (! p_idSystemConversion.contains(foreignTable.toUpperCase() + "." + foreignID)) {
							StringBuilder sqlCheckLocalUU = new StringBuilder()
									.append("SELECT ").append(foreignTable).append("_ID FROM ").append(foreignTable)
									.append(" WHERE ").append(uuidCol).append("=?");
							int localID = DB.getSQLValueEx(get_TrxName(), sqlCheckLocalUU.toString(), foreignUU);
							if (localID < 0) {
								p_errorList.add("Column " + tableName + "." + columnName +  " has system reference not convertible, "
										+ foreignTable + "." + uuidCol + "=" + foreignUU);
								continue;
							}
							DB.executeUpdateEx(insertConversionId,
									new Object[] {getAD_PInstance_ID(), foreignTable.toUpperCase(), foreignID, localID},
									get_TrxName());
							p_idSystemConversion.add(foreignTable.toUpperCase() + "." + foreignID);
						}
					}
				}
			} catch (SQLException e) {
				throw new AdempiereException("Could not execute external query: " + sqlForeignClient + "\nCause = " + e.getLocalizedMessage());
			} finally {
				DB.close(rsFC, stmtFC);
			}
		}
		// add to the list of verified columns
		p_columnsVerified.add(tableName.toUpperCase() + "." + columnName.toUpperCase());
	}

	private int countInExternal(String sql) {
		int cnt = 0;
		sql = DB.getDatabase().convertStatement(sql.toString());
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			stmt = externalConn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			rs = stmt.executeQuery();
			if (rs.next())
				cnt = rs.getInt(1);
		} catch (SQLException e) {
			throw new AdempiereException("Could not execute external query: " + sql + "\nCause = " + e.getLocalizedMessage());
		} finally {
			DB.close(rs, stmt);
		}
		return cnt;
	}

	private void moveClient() {
		// first do the validation, process cannot be executed if there are blocking situations
		// validation construct the list of tables and columns to process
		// NOTE that the whole process will be done in a single transaction, foreign keys will be validated on commit

		List<MTable> tables = new Query(getCtx(), MTable.Table_Name,
				"IsView='N' AND " + p_excludeTablesWhere,
				get_TrxName())
				.setOnlyActiveRecords(true)
				.setOrderBy("TableName")
				.list();

		// create the ID conversions
		for (MTable table : tables) {
			String tableName = table.getTableName();
			if (! p_tablesVerified.contains(tableName.toUpperCase())) {
				continue;
			}
			if (! p_columnsVerified.contains(tableName.toUpperCase() + "." + tableName.toUpperCase() + "_ID")) {
				continue;
			}
			statusUpdate("Converting IDs for table " + tableName);
			StringBuilder selectGetIdsSB = new StringBuilder()
					.append("SELECT ").append(tableName).append(".").append(tableName).append("_ID FROM ").append(tableName);
			if (! "AD_Client".equalsIgnoreCase(tableName)) {
				selectGetIdsSB.append(" JOIN AD_Client ON (").append(tableName).append(".AD_Client_ID=AD_Client.AD_Client_ID)");
			}
			selectGetIdsSB.append(" WHERE ").append(p_whereClient)
			.append(" ORDER BY ").append(tableName).append("_ID");
			String selectGetIds = DB.getDatabase().convertStatement(selectGetIdsSB.toString());
			PreparedStatement stmtGI = null;
			ResultSet rsGI = null;
			try {
				stmtGI = externalConn.prepareStatement(selectGetIds, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
				rsGI = stmtGI.executeQuery();
				while (rsGI.next()) {
					int sourceID = rsGI.getInt(1);
					int targetID = DB.getNextID(getAD_Client_ID(), tableName, get_TrxName());
					DB.executeUpdateEx(insertConversionId,
							new Object[] {getAD_PInstance_ID(), tableName.toUpperCase(), sourceID, targetID},
							get_TrxName());
				}
			} catch (SQLException e) {
				throw new AdempiereException("Could not execute external query: " + selectGetIds + "\nCause = " + e.getLocalizedMessage());
			} finally {
				DB.close(rsGI, stmtGI);
			}

		}

		// get the source data and insert into target converting the IDs
		for (MTable table : tables) {
			String tableName = table.getTableName();
			if (! p_tablesVerified.contains(tableName.toUpperCase())) {
				continue;
			}
			statusUpdate("Inserting data for table " + tableName);
			StringBuilder valuesSB = new StringBuilder();
			StringBuilder columnsSB = new StringBuilder();
			StringBuilder qColumnsSB = new StringBuilder();
			int ncols = 0;
			List<MColumn> columns = new ArrayList<MColumn>();
			for (MColumn column : table.getColumns(false)) {
				if (!column.isActive() || column.getColumnSQL() != null) {
					continue;
				}
				String columnName = column.getColumnName();
				if (! p_columnsVerified.contains(tableName.toUpperCase() + "." + columnName.toUpperCase())) {
					continue;
				}
				if (columnsSB.length() > 0) {
					qColumnsSB.append(",");
					columnsSB.append(",");
					valuesSB.append(",");
				}
				qColumnsSB.append(tableName).append(".").append(columnName);
				columnsSB.append(columnName);
				valuesSB.append("?");
				columns.add(column);
				ncols++;
			}
			StringBuilder insertSB = new StringBuilder()
					.append("INSERT INTO ").append(tableName).append("(").append(columnsSB).append(") VALUES (").append(valuesSB).append(")");
			StringBuilder selectGetDataSB = new StringBuilder()
					.append("SELECT ").append(qColumnsSB)
					.append(" FROM ").append(tableName);
			if ("AD_PInstance_Log".equalsIgnoreCase(tableName)) {
				selectGetDataSB.append(" JOIN AD_PInstance ON (AD_PInstance_Log.AD_PInstance_ID=AD_PInstance.AD_PInstance_ID)");
				selectGetDataSB.append(" JOIN AD_Client ON (AD_PInstance.AD_Client_ID=AD_Client.AD_Client_ID)");
			} else if (! "AD_Client".equalsIgnoreCase(tableName)) {
				selectGetDataSB.append(" JOIN AD_Client ON (").append(tableName).append(".AD_Client_ID=AD_Client.AD_Client_ID)");
			}
			selectGetDataSB.append(" WHERE ").append(p_whereClient);
			String selectGetData = DB.getDatabase().convertStatement(selectGetDataSB.toString());
			PreparedStatement stmtGD = null;
			ResultSet rsGD = null;
			Object[] parameters = new Object[ncols];
			try {
				stmtGD = externalConn.prepareStatement(selectGetData, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
				rsGD = stmtGD.executeQuery();
				while (rsGD.next()) {
					for (int i = 0; i < ncols; i++) {
						MColumn column = columns.get(i);
						String columnName = column.getColumnName();
						String convertTable = column.getReferenceTableName();
						if ((tableName + "_ID").equalsIgnoreCase(columnName)) {
							convertTable = tableName;
						} else if ("C_BPartner".equalsIgnoreCase(tableName) && "AD_OrgBP_ID".equalsIgnoreCase(columnName)) {
							// Special case for C_BPartner.AD_OrgBP_ID defined as Button in dictionary
							convertTable = "AD_Org";
						} else if (convertTable != null
								&& ("AD_Ref_List".equalsIgnoreCase(convertTable)
										|| "AD_Language".equalsIgnoreCase(columnName)
										|| "EntityType".equalsIgnoreCase(columnName))) {
							convertTable = "";
						} else if ("Record_ID".equalsIgnoreCase(columnName) && table.getColumnIndex("AD_Table_ID") > 0) {
							// Special case for Record_ID
							int tableId = rsGD.getInt("AD_Table_ID");
							convertTable = getExternalTableName(tableId);
						} else if ("AD_Preference".equalsIgnoreCase(tableName) && "Value".equalsIgnoreCase(columnName)) {
							// Special case for Record_ID
							String att = rsGD.getString("Attribute");
							if (att.toUpperCase().endsWith("_ID")) {
								convertTable = att.substring(0, att.length()-3);
								if ("C_DocTypeTarget".equals(convertTable)) {
									convertTable = "C_DocType";
								}
							} else {
								convertTable = "";
							}
						}
						if (! Util.isEmpty(convertTable)) {
							// Foreign - potential ID conversion
							int id = rsGD.getInt(i + 1);
							if (rsGD.wasNull()) {
								parameters[i] = null;
							} else {
								if (id >= MTable.MAX_OFFICIAL_ID) {
									int convertedId = -1;
									final String query = "SELECT Target_ID FROM T_MoveClient WHERE AD_PInstance_ID=? AND TableName=? AND Source_ID=?";
									try {
										convertedId = DB.getSQLValueEx(get_TrxName(),
												query,
												getAD_PInstance_ID(), convertTable.toUpperCase(), id);
									} catch (Exception e) {
										throw new AdempiereException("Could not execute query: " + query + "\nCause = " + e.getLocalizedMessage());
									}
									if (convertedId < 0) {
										throw new AdempiereException("Found orphan record in column " + tableName + "." + columnName + ": " + id);
									}
									id = convertedId;
								}
								if ("AD_Preference".equalsIgnoreCase(tableName) && "Value".equalsIgnoreCase(columnName)) {
									parameters[i] = String.valueOf(id);
								} else {
									parameters[i] = id;
								}
							}
						} else {
							parameters[i] = rsGD.getObject(i + 1);
							if (rsGD.wasNull()) {
								parameters[i] = null;
							}
						}
					}
					try {
						DB.executeUpdateEx(insertSB.toString(), parameters, get_TrxName());
					} catch (Exception e) {
						throw new AdempiereException("Could not execute: " + insertSB + "\nCause = " + e.getLocalizedMessage());
					}
				}
			} catch (SQLException e) {
				throw new AdempiereException("Could not execute external query: " + selectGetData + "\nCause = " + e.getLocalizedMessage());
			} finally {
				DB.close(rsGD, stmtGD);
			}

		}

		// commit - here it can throw errors because of foreign keys, verify and inform
		statusUpdate("Committing.  Validating foreign keys");
		try {
			commitEx();
		} catch (SQLException e) {
			throw new AdempiereException("Could not commit,\nCause: " + e.getLocalizedMessage());
		}
	}

	private String getExternalTableName(int tableId) {
		String tableName = null;
		String sql = DB.getDatabase().convertStatement("SELECT TableName FROM AD_Table WHERE AD_Table_ID=?");
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			stmt = externalConn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			stmt.setInt(1, tableId);
			rs = stmt.executeQuery();
			if (rs.next())
				tableName = rs.getString(1);
		} catch (SQLException e) {
			throw new AdempiereException("Could not execute external query: " + sql + "\nCause = " + e.getLocalizedMessage());
		} finally {
			DB.close(rs, stmt);
		}
		return tableName;
	}

}
