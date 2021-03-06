package com.steatoda.commons.fields.service.crud.sql;

import com.google.common.collect.Sets;
import com.steatoda.commons.fields.FieldEnum;
import com.steatoda.commons.fields.FieldGraph;
import com.steatoda.commons.fields.FieldUnavailableException;
import com.steatoda.commons.fields.HasEntityFields;
import com.steatoda.commons.fields.service.crud.CRUDException;
import com.steatoda.commons.fields.service.crud.CRUDFieldsService;
import com.steatoda.commons.sql.DMLColumn;
import com.steatoda.commons.sql.statement.DeleteStatement;
import com.steatoda.commons.sql.statement.InsertStatement;
import com.steatoda.commons.sql.statement.SelectStatement;
import com.steatoda.commons.sql.statement.UpdateStatement;
import com.steatoda.commons.sql.where.WhereTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class CRUDFieldsSqlService<I, C extends HasEntityFields<I, C, F>, F extends Enum<F> & FieldEnum, S> implements CRUDFieldsService<I, C, F, S> {

	public CRUDFieldsSqlService(String tableName, Set<F> mandatoryFields, Set<F> modifiableFields) {
		this.tableName = tableName;
		this.mandatoryFields = mandatoryFields;
		this.modifiableFields = modifiableFields;
	}

	public String getTableName() { return tableName; }
	public Set<F> getMandatoryFields() { return Collections.unmodifiableSet(mandatoryFields); }
	public Set<F> getModifiableFields() { return Collections.unmodifiableSet(modifiableFields); }

	@Override
	public C get(I id, FieldGraph<F> graph) {

		try (PreparedStatement statement = new SelectStatement(tableName)
			.addTableDQLColumns(getDQLColumns(graph), this::decorateDQLColumn)
			.addWhereTerms(getIdentityWhereTerm(id))
			.build(getConnection())
		) {
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? readResultSet(resultSet, graph) : null;
			}
		} catch (SQLException e) {
			throw new CRUDException("Unable to find entity with id " + id, e);
		}

	}

	@Override
	public void create(C entity, FieldGraph<F> graph) {

		checkMandatoryFields(entity);

		try (PreparedStatement statement = buildCreateStatement(entity, graph).build(getConnection())) {
			if (statement.execute())
				try (ResultSet resultSet = statement.getResultSet()) {
					if (!resultSet.next())
						throw new CRUDException("Unable to insert new entity into db!");
					readResultSet(entity, resultSet, graph);
				}
		} catch (SQLException e) {
			// note: can't log entity itself because it doesn't have even ID (and most toString methods defaults to logging ID)
			throw new CRUDException("Unable to create entity", e);
		}

		Log.trace("Created {}", entity);

	}

	protected InsertStatement buildCreateStatement(C entity, FieldGraph<F> graph) {
		return new InsertStatement(tableName)
			.addDMLColumns(getDMLColumns(entity))
			.addTableDQLColumns(getDQLColumns(graph), this::decorateDQLColumn);
	}

	/**
	 * <p>Modified <code>entity</code> with properties set in <code>entity</code>.</p>
	 *
	 * <p>Override to modify fields not marked as modifiable.</p>
	 *
	 * @param entity entity to modify
	 * @param patch patch describing attributes to modify
	 * @param graph return graph
	 */
	@Override
	public void modify(C entity, C patch, FieldGraph<F> graph) {

		// NOTE: be carefully not to modify patch, because it may hold data valuable to caller (e.g. regarding modifying sub-entites etc.)

		patch = patch.cloneFlat();
		patch.intersect(FieldGraph.of(modifiableFields));

		UpdateStatement updateStatement = buildModifyStatement(entity, patch, graph);

		if (updateStatement == null) {
			entity.extend(graph, this);
			return;
		}

		try (PreparedStatement statement = updateStatement.build(getConnection())) {
			if (statement.execute())
				try (ResultSet resultSet = statement.getResultSet()) {
					if (!resultSet.next())
						throw new CRUDException("Unable to update entity " + entity);
					readResultSet(entity, resultSet, graph);
				}
		} catch (SQLException e) {
			throw new CRUDException("Unable to modify entity " + entity, e);
		}

		Log.trace("Modified {}", entity);

	}

	/**
	 * Constructs statement used in {@link #modify(HasEntityFields, HasEntityFields, FieldGraph)}.
	 *
	 * @param entity entity to modify
	 * @param patch patch describing attributes to modify
	 * @param graph return graph
	 *
	 * @return {@link UpdateStatement} or {@code null}, if there's nothing to update/modify
	 */
	protected UpdateStatement buildModifyStatement(C entity, C patch, FieldGraph<F> graph) {

		List<DMLColumn> dmlColumns = getDMLColumns(patch);

		if (dmlColumns.isEmpty())
			return null;	// nothing to update

		return new UpdateStatement(tableName)
			.addDMLColumns(dmlColumns)
			.addTableDQLColumns(getDQLColumns(graph), this::decorateDQLColumn)
			.addWhereTerms(getIdentityWhereTerm(entity));

	}

	@Override
	public void delete(C entity) {

		try (PreparedStatement statement = new DeleteStatement(tableName)
			.addWhereTerms(getIdentityWhereTerm(entity))
			.build(getConnection())
		) {
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new CRUDException("Unable to delete entity " + entity, e);
		}

		Log.trace("Deleted {}", entity);

	}

	@Override
	public List<C> getAllFieldValues(S selector, Set<F> fields) {

		List<C> entites = new ArrayList<>();

		List<String> distinctOnColumns = fields.stream().map(this::getColumn).filter(Objects::nonNull).collect(Collectors.toList());
		if (distinctOnColumns.isEmpty())
			throw new FieldUnavailableException(fields);

		SelectStatement selectStatement = new SelectStatement(tableName)
			.addTableDistinctOnColumns(distinctOnColumns)
			.addTableDQLColumns(getDQLColumns(fields), this::decorateDQLColumn)
		;
		if (selector != null)
			selectStatement.addWhereTerms(getSelectorWhereTerm(selector));

		try (PreparedStatement statement = selectStatement.build(getConnection())) {
			try (ResultSet resultSet = statement.executeQuery()) {
				FieldGraph<F> graph = FieldGraph.of(fields);
				while (resultSet.next()) {
					C entity = readResultSet(resultSet, graph);
					entity.setId(null);	// not needed and it doesn't make sense anyway...
					entites.add(entity);
				}
			}
		} catch (SQLException e) {
			throw new CRUDException("Unable to list all values for fields " + fields, e);
		}

		return entites;

	}

	@Override
	public int count(S selector) {

		try (PreparedStatement statement = new SelectStatement(tableName)
			.addDQLColumns("COUNT(*)")
			.addWhereTerms(getSelectorWhereTerm(selector))
			.build(getConnection())
		) {
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next())
					throw new CRUDException("Query did not return any rows?!!");
				return resultSet.getInt(1);
			}
		} catch (SQLException e) {
			throw new CRUDException("Unable to count entities", e);
		}

	}

	@Override
	public List<C> list(S selector, FieldGraph<F> graph) {

		return doList(buildListStatement(selector, graph), graph);

	}

	protected SelectStatement buildListStatement(S selector, FieldGraph<F> graph) {
		return buildListStatement(selector, graph, SelectStatement::new);
	}

	protected SelectStatement buildListStatement(S selector, FieldGraph<F> graph, Function<String, SelectStatement> selectStatementBuilder) {
		return selectStatementBuilder.apply(tableName)
			.addTableDQLColumns(getDQLColumns(graph), this::decorateDQLColumn)
			.addWhereTerms(getSelectorWhereTerm(selector));
	}

	protected List<C> doList(SelectStatement select, FieldGraph<F> graph) {

		List<C> entites = new ArrayList<>();

		try (PreparedStatement statement = select.build(getConnection())) {
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next())
					entites.add(readResultSet(resultSet, graph));
			}
		} catch (SQLException e) {
			throw new CRUDException("Unable to list entities", e);
		}

		return entites;

	}

	protected abstract Connection getConnection();
	protected abstract List<String> getDQLColumns(Collection<F> fields);
	protected abstract List<DMLColumn> getDMLColumns(C entity);
	protected abstract String getColumn(F field);
	protected abstract WhereTerm getIdentityWhereTerm(I id);
	protected abstract WhereTerm getSelectorWhereTerm(S selector);
	protected abstract void readResultSet(C entity, ResultSet resultSet, FieldGraph<F> graph) throws SQLException;

	/**
	 * Returns concatenated DQL columns for given fields for inclusion into hand-written SQL statements.
	 *
	 * @param fields fields describing columns
	 *
	 * @return DQL columns for given fields
	 */
	protected String getDQLColumnsExpression(Collection<F> fields) {
		return getDQLColumns(fields).stream().map(column -> tableName + "." + column).collect(Collectors.joining(", "));
	}

	protected String decorateDQLColumn(String column) {
		return column;
	}

	protected void checkMandatoryFields(C entity) {
		Set<F> missingFields = Sets.difference(mandatoryFields, entity.getFields());
		if (!missingFields.isEmpty())
			throw new FieldUnavailableException(missingFields);
	}

	protected C readResultSet(ResultSet resultSet, FieldGraph<F> graph) throws SQLException {
		C entity = instance();
		readResultSet(entity, resultSet, graph);
		return entity;
	}

	protected WhereTerm getIdentityWhereTerm(C entity) {
		return getIdentityWhereTerm(entity.getId());
	}

	private static final Logger Log = LoggerFactory.getLogger(CRUDFieldsSqlService.class);

	private final String tableName;
	private final Set<F> mandatoryFields;
	private final Set<F> modifiableFields;

}
