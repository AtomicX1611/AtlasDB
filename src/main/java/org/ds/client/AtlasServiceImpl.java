package org.ds.client;

import io.grpc.stub.StreamObserver;
import org.ds.Replication.Node.Node;
import org.ds.proto.*;

import java.util.logging.Logger;



public class AtlasServiceImpl extends AtlasServiceGrpc.AtlasServiceImplBase {
    private static final Logger logger = Logger.getLogger(AtlasServiceImpl.class.getName());

    private final Node node;

    public AtlasServiceImpl(Node node) {
        this.node = node;
    }


    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(notLeaderPut());
            observer.onCompleted();
            return;
        }
        try {
            node.propose("SET " + request.getKey() + " " + request.getValue());
            observer.onNext(PutResponse.newBuilder().setSuccess(true).build());
        } catch (NotLeaderException e) {
            observer.onNext(PutResponse.newBuilder()
                .setSuccess(false)
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(e.getCurrentLeaderId()))
                .build());
        } catch (Exception e) {
            logger.warning("[" + node.getId() + "] PUT failed: " + e.getMessage());
            observer.onNext(PutResponse.newBuilder()
                .setSuccess(false)
                .setError(e.getMessage())
                .build());
        }
        observer.onCompleted();
    }


    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(GetResponse.newBuilder()
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
                .build());
            observer.onCompleted();
            return;
        }
        String value = node.getStateMachine().get(request.getKey());
        observer.onNext(GetResponse.newBuilder()
            .setValue(value)
            .setFound(!value.equals("(nil)"))
            .build());
        observer.onCompleted();
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(notLeaderDel());
            observer.onCompleted();
            return;
        }
        try {
            node.propose("DEL " + request.getKey());
        
            observer.onNext(DeleteResponse.newBuilder().setDeleted(true).build());
        } catch (NotLeaderException e) {
            observer.onNext(DeleteResponse.newBuilder()
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(e.getCurrentLeaderId()))
                .build());
        } catch (Exception e) {
            logger.warning("[" + node.getId() + "] DEL failed: " + e.getMessage());
            observer.onNext(DeleteResponse.newBuilder()
                .setError(e.getMessage())
                .build());
        }
        observer.onCompleted();
    }

    

    private PutResponse notLeaderPut() {
        return PutResponse.newBuilder()
            .setSuccess(false)
            .setError("NOT_LEADER")
            .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
            .build();
    }

    private DeleteResponse notLeaderDel() {
        return DeleteResponse.newBuilder()
            .setError("NOT_LEADER")
            .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
            .build();
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
