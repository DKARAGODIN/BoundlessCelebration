package pro.karagodin;

import com.yandex.ydb.auth.iam.CloudAuthProvider;
import com.yandex.ydb.core.grpc.GrpcTransport;
import com.yandex.ydb.table.TableClient;
import com.yandex.ydb.table.query.DataQueryResult;
import com.yandex.ydb.table.query.Params;
import com.yandex.ydb.table.rpc.grpc.GrpcTableRpc;
import com.yandex.ydb.table.transaction.TxControl;
import com.yandex.ydb.table.values.PrimitiveValue;
import yandex.cloud.sdk.auth.provider.ComputeEngineCredentialProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EntityManager {

	private final String database;
	private final String endpoint;

	public EntityManager(String database, String endpoint) {
		this.database = database;
		this.endpoint = endpoint;
	}

	public List<Leader> getLeaders() {
		var leaders = new ArrayList<Leader>();
		execute("select * from LEADERBOARD order by SCORE desc LIMIT 10", Params.empty(), result -> {
			var rs = result.getResultSet(0);
			while (rs.next()) {
				var id = rs.getColumn("ID").getInt32();
				var name = rs.getColumn("NAME").getUtf8();
				var score = rs.getColumn("SCORE").getInt32();
				leaders.add(new Leader(id, name, score));
			}
		});
		return leaders;
	}

	public Leader getLeader(Leader request) {
		var wrapper = new Object(){ Leader leader = null; };
		execute("declare $NAME as Utf8; select * from LEADERBOARD where NAME = $NAME", Params.of("$NAME", PrimitiveValue.utf8(request.getName())), result -> {
			var rs = result.getResultSet(0);
			while (rs.next()) {
				var id = rs.getColumn("ID").getInt32();
				var name = rs.getColumn("NAME").getUtf8();
				var score = rs.getColumn("SCORE").getInt32();
				wrapper.leader = new Leader(id, name, score);
			}
		});
		return wrapper.leader;
	}

	public long getSnowballs() {
		var wrapper = new Object(){ long s = 0; };
		execute("select sum(SCORE) as s from LEADERBOARD ", Params.empty(), result -> {
			var rs = result.getResultSet(0);
			rs.next();
			wrapper.s = rs.getColumn("s").getInt64();
		});
		return wrapper.s;
	}

	private Integer getCurrentSnowballs(String name) {
		var wrapper = new Object(){ Integer i = null; };
		execute("declare $NAME as Utf8; select SCORE from LEADERBOARD where NAME = $NAME", Params.of("$NAME", PrimitiveValue.utf8(name)), result -> {
			var rs = result.getResultSet(0);
			if (rs.next()) {
				wrapper.i = rs.getColumn("SCORE").getInt32();
			}
		});
		return wrapper.i;
	}

	public void postLeader(Leader leader) {
		Integer previousSnowballs = getCurrentSnowballs(leader.getName());
		if (previousSnowballs == null) {
			execute("declare $NAME as Utf8;" +
							"declare $SCORE as Int32;" +
							"insert into LEADERBOARD(NAME, SCORE) values ($NAME, $SCORE)",
					Params.of("$NAME", PrimitiveValue.utf8(leader.getName()),
							"$SCORE", PrimitiveValue.int32(leader.getScore())),
					null
			);
		} else if (previousSnowballs < leader.getScore()) {
			execute("declare $NAME as Utf8;" +
							"declare $SCORE as Int32;" +
							"update LEADERBOARD set SCORE = $SCORE where NAME = $NAME",
					Params.of("$NAME", PrimitiveValue.utf8(leader.getName()),
							"$SCORE", PrimitiveValue.int32(leader.getScore())),
					null
			);
		}
	}

	public void deleteTable() {
		execute("delete from LEADERBOARD", Params.empty(), null);
	}

	private void execute(String query, Params params, Consumer<DataQueryResult> callback) {
		var authProvider = CloudAuthProvider.newAuthProvider(ComputeEngineCredentialProvider.builder().build());
		var transport = GrpcTransport.forEndpoint(endpoint, database).withAuthProvider(authProvider).withSecureConnection().build();
		var tableClient = TableClient.newClient(GrpcTableRpc.useTransport(transport)).build();

		var session = tableClient.createSession()
				.join()
				.expect("Error: can't create session");

		var preparedQuery = session.prepareDataQuery(query)
				.join()
				.expect("Error: query preparation failed");

		var result = preparedQuery.execute(TxControl.serializableRw().setCommitTx(true), params)
				.join()
				.expect("Error: query execution failed");

		if (callback != null) {
			callback.accept(result);
		}
	}
}
