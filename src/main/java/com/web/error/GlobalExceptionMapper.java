package com.web.error;

import com.web.model.ErrorResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    public Response toResponse(Exception exception) {
        if (exception instanceof com.web.error.UserAlreadyExistsException) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Registration failed"))
                    .build();
        }

        if (exception instanceof com.web.error.AuthenticationFailedException || exception instanceof io.smallrye.jwt.auth.principal.ParseException) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .build();
        }

        if (exception instanceof WebApplicationException webAppException) {
            String message = webAppException.getMessage();
            int status = webAppException.getResponse().getStatus();
            return Response.status(status).entity(new ErrorResponse(message)).build();
        }

        LOG.error("Internal Server Error", exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("Internal Server Error"))
                .build();
    }
}
