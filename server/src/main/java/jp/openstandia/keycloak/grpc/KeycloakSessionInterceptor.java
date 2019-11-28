package jp.openstandia.keycloak.grpc;

import io.grpc.*;
import org.jboss.logging.Logger;
import org.keycloak.common.ClientConnection;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class KeycloakSessionInterceptor implements ServerInterceptor {

    private static final Logger logger = Logger.getLogger(DefaultGrpcServerProviderFactory.class);

    private final KeycloakSessionFactory sessionFactory;

    private KeycloakSessionInterceptor(KeycloakSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public static ServerInterceptor instance(KeycloakSessionFactory sessionFactory) {
        return new KeycloakSessionInterceptor(sessionFactory);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        final KeycloakSession session = sessionFactory.create();

        // How to get remote address/port
        // https://github.com/grpc/grpc-java/blob/30b59885b7496b53eb17f64ba1d822c2d9a6c69a/interop-testing/src/main/java/io/grpc/testing/integration/AbstractInteropTest.java#L1627-L1639

        final String inetSocketString = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
        final String host = inetSocketString.substring(0, inetSocketString.lastIndexOf(':'));
        final String port = inetSocketString.substring(inetSocketString.lastIndexOf(':'));

        final String localInetSocketString = call.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR).toString();
        final String localHost = localInetSocketString.substring(0, localInetSocketString.lastIndexOf(':'));
        final String localPort = localInetSocketString.substring(localInetSocketString.lastIndexOf(':'));

        final ClientConnection connection = new ClientConnection() {
            @Override
            public String getRemoteAddr() {
                return host;
            }

            @Override
            public String getRemoteHost() {
                return host;
            }

            @Override
            public int getRemotePort() {
                return Integer.parseInt(port);
            }

            @Override
            public String getLocalAddr() {
                return localHost;
            }

            @Override
            public int getLocalPort() {
                return Integer.parseInt(localPort);
            }
        };
        session.getContext().setConnection(connection);
        

//        final KeycloakTransactionManager tx = session.getTransactionManager();
//        tx.begin();

//        GrpcAdminRoot adminRoot = new GrpcAdminRoot(session);
//        AdminAuth adminAuth = adminRoot.authenticateRealmAdminRequest(headers);

        ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> serverCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
//                if (tx.isActive()) {
//                    if (tx.getRollbackOnly()) {
//                        tx.rollback();
//                    } else {
//                        tx.commit();
//                    }
//                }
                if (!status.isOk()) {
                    logger.errorv("Error in calling gRPC service. status={0}, metadata={1}", status, trailers);
                }
                closeSession(session);
                super.close(status, trailers);
            }
        };

        String token = headers.get(Constant.AuthorizationMetadataKey);

        Context ctx = Context.current()
                .withValue(Constant.KeycloakSessionContextKey, session)
                .withValue(Constant.AuthorizationHeaderContextKey, token);
//                .withValue(Constant.AdminAuthContextKey, adminAuth);
        return Contexts.interceptCall(ctx, serverCall, headers, next);
    }

    private void closeSession(KeycloakSession session) {
        // KeycloakTransactionCommitter is responsible for committing the transaction, but if an exception is thrown it's not invoked and transaction
        // should be rolled back
        if (session.getTransactionManager() != null && session.getTransactionManager().isActive()) {
            session.getTransactionManager().rollback();
        }

        session.close();
//        Resteasy.clearContextData();
    }
}
