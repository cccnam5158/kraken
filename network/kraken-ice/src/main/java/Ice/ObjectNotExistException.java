// **********************************************************************
//
// Copyright (c) 2003-2010 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

// Ice version 3.4.1

package Ice;

// <auto-generated>
//
// Generated from file `LocalException.ice'
//
// Warning: do not edit this file.
//
// </auto-generated>


/**
 * This exception is raised if an object does not exist on the server,
 * that is, if no facets with the given identity exist.
 * 
 **/
public class ObjectNotExistException extends RequestFailedException
{
    public ObjectNotExistException()
    {
        super();
    }

    public ObjectNotExistException(Identity id, String facet, String operation)
    {
        super(id, facet, operation);
    }

    public String
    ice_name()
    {
        return "Ice::ObjectNotExistException";
    }
}
