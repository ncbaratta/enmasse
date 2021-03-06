/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.api.v1.http;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressList;
import io.enmasse.address.model.AddressOrAddressList;
import io.enmasse.k8s.api.SchemaProvider;
import io.enmasse.k8s.api.AddressSpaceApi;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.time.Clock;

/**
 * HTTP API for operating on addresses within an address space
 */
@Path("/apis/enmasse.io/{version:v1alpha1|v1beta1}/namespaces/{namespace}/addressspaces/{addressSpace}/addresses")
public class HttpNestedAddressService extends HttpAddressServiceBase {
    public HttpNestedAddressService(AddressSpaceApi addressSpaceApi, SchemaProvider schemaProvider, Clock clock) {
        super(addressSpaceApi, schemaProvider, clock);
    }

    private void validateAddressName(final String addressName) {
        if ( addressName == null ) {
            return;
        }

        if ( addressName.indexOf('.') < 0 ) {
            throw new BadRequestException("Invalid address name format. Must be: <address-space>.<address>");
        }
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    public Response getAddressList(@Context SecurityContext securityContext, @HeaderParam("Accept") String acceptHeader, @PathParam("namespace") String namespace, @PathParam("addressSpace") String addressSpace, @QueryParam("address") String address, @QueryParam("labelSelector") String labelSelector) throws Exception {
        /**
         * Only in the case of the "get list" operation don't we use the "metadata name", but the actual address name.
         * So we must not concatenate the addressspace with the name in *this case*.
         */
        return internalGetAddressList(securityContext, acceptHeader, namespace, addressSpace, address, labelSelector);
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    @Path("{addressName}")
    public Response getAddress(@Context SecurityContext securityContext, @HeaderParam("Accept") String acceptHeader, @PathParam("namespace") String namespace, @PathParam("addressSpace") String addressSpace, @PathParam("addressName") String address) throws Exception {
        validateAddressName(address);
        return internalGetAddress(securityContext, acceptHeader, namespace, addressSpace, address);
    }

    @POST
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    public Response createAddress(@Context SecurityContext securityContext, @Context UriInfo uriInfo, @PathParam("namespace") String namespace, @PathParam("addressSpace") String addressSpace, @NotNull @Valid AddressOrAddressList payload) throws Exception {
        if ( payload instanceof Address ) {
            return internalCreateAddress(securityContext, uriInfo, namespace, addressSpace, (Address) payload);
        } else if ( payload instanceof AddressList) {
            return internalCreateAddresses(securityContext, uriInfo, namespace, addressSpace, (AddressList) payload);
        } else {
            throw new BadRequestException("Unknown payload type");
        }
    }

    @PUT
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    @Path("{addressName}")
    public Response replaceAddress(@Context SecurityContext securityContext, @PathParam("namespace") String namespace, @PathParam("addressSpace") String addressSpace, @PathParam("addressName") String addressName, @NotNull @Valid Address payload) throws Exception {
        validateAddressName(addressName);
        return internalReplaceAddress(securityContext, namespace, addressSpace, addressName, payload);
    }

    @DELETE
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    @Path("{addressName}")
    public Response deleteAddress(@Context SecurityContext securityContext, @PathParam("namespace") String namespace, @PathParam("addressSpace") String addressSpace, @PathParam("addressName") String addressName) throws Exception {
        validateAddressName(addressName);
        return internalDeleteAddress(securityContext, namespace, addressSpace, addressName);
    }
}
