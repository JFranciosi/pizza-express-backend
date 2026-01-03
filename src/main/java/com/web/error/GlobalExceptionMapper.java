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

    @Override
    public Response toResponse(Exception exception) {
        if (exception instanceof WebApplicationException webAppException) {
            String message = webAppException.getMessage();
            int status = webAppException.getResponse().getStatus();

            if (message != null) {
                if (message.contains("Email already in use") || message.contains("Username already in use")) {
                    message = "Registration failed";
                    status = 400;
                } else if (message.contains("User not found") || message.contains("Invalid credentials")) {
                    if (status == 404 || status == 401) {
                    }
                }
            }

            return Response.status(status).entity(new ErrorResponse(message)).build();
        }

        if (exception instanceof io.smallrye.jwt.auth.principal.ParseException) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid or expired token"))
                    .build();
        }

        LOG.error("Internal Server Error", exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("Internal Server Error"))
                .build();
    }
}
