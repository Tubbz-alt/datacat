
from collections import OrderedDict, MutableMapping
import json

class DatacatRecord(object):
    def __init__(self, pk=None, path=None, **kwargs):
        if path is not None:
            self.path = path
        if pk is not None:
            self.pk = pk

class DatacatNode(DatacatRecord):
    def __init__(self, name=None, parentPk=None, **kwargs):
        super(DatacatNode, self).__init__(**kwargs)
        if name is not None:
            self.name = name
        if parentPk is not None:
            self.parentPk = parentPk

class Container(DatacatNode):
    def __init__(self, **kwargs):
        super(Container, self).__init__(**kwargs)
        for k,v in kwargs.items():
            if k != "_type" and not hasattr(self, k) and v:
                self.__dict__[k] = v

class Folder(Container):
    pass

class Group(Container):
    pass

class Dataset(DatacatNode):
    REQ_JSON_ALLOWED = ("name path dataType fileFormat metadata" \
        + "versionId processInstance taskName versionMetadata locations").split(" ")

    def __init__(self,
                 dataType=None,
                 fileFormat=None,
                 metadata=None,
                 versionId=None,
                 processInstance=None,
                 taskName=None,
                 versionMetadata=None,
                 locations=None,
                 **kwargs):
        super(Dataset, self).__init__(**kwargs)
        if dataType:
            self.dataType=dataType
        if fileFormat:
            self.fileFormat=fileFormat
        if metadata:
            self.metadata=metadata
        if versionId:
            self.versionId=versionId
        if processInstance:
            self.processInstance=processInstance
        if taskName:
            self.taskName=taskName
        if versionMetadata:
            self.versionMetadata=versionMetadata
        if locations:
            self.locations=locations
        # ignore _type for now
        for k,v in kwargs.items():
            if k != "_type" and not hasattr(self, k) and v:
                self.__dict__[k] = v

class DatasetLocation(DatacatRecord):
    def __init__(self, site=None, resource=None, **kwargs):
        super(DatasetLocation, self).__init__(**kwargs)
        if site:
            self.site = site
        if resource:
            self.resource = resource
        for k,v in kwargs.items():
            if k != "_type" and not hasattr(self, k) and v:
                self.__dict__[k] = v

class Metadata(MutableMapping):
    def __init__(self, seq=None):
        self.dct = OrderedDict(seq) if seq else OrderedDict()

    def __getitem__(self, key):
        return self.dct.__getitem__(key)

    def __setitem__(self, key, value):
        self.dct.__setitem__(key, value)

    def __delitem__(self, key):
        return self.dct.__delitem__(key)

    def __iter__(self):
        return self.dct.__iter__()

    def __len__(self):
        return self.dct.__len__()

def unpack(content, default_type=None):
    def type_hook(raw):
        try:
            ret = _default_hook(raw)
        except TypeError as e:
            ret = default_type(**raw)
        return ret
    return json.loads(content, object_hook=_default_hook if not default_type else type_hook)

def pack(object):
    return json.dumps(object, default=_default_serializer)

def _default_serializer(obj):
    try:
        if isinstance(obj, Dataset):
            ret = {}
            for k,v in obj.__dict__.items():
                if v:
                    if k in ("metadata", "versionMetadata"):
                        ret[k] = Metadata(v)
                    else:
                        ret[k] = v
            return ret
        if isinstance(obj, DatacatNode):
            ret = {}
            for k,v in obj.__dict__.items():
                if v:
                    if k in ("metadata", "versionMetadata"):
                        ret[k] = Metadata(v)
                    else:
                        ret[k] = v
            return ret
        if isinstance(obj, DatacatRecord):
            ret = {}
            for k,v in obj.__dict__.items():
                if v:
                   ret[k] = v
            return ret
        if isinstance(obj, Metadata):
            type_mapping = {int:"integer", long:"integer", float:"decimal", unicode:"string", str:"string"}
            ret = []
            for k,v in obj.dct.items():
                ret.append(OrderedDict([("key",k), ("value",v), ("type",type_mapping[type(v)])]))
            return ret
        iterable = iter(obj)
    except TypeError as e:
        print e
    else:
        return list(iterable)
    return json.JSONEncoder.default(obj)

def _default_hook(raw):
    def fix_md(name, d):
        if name in d:
            d[name] = Metadata(d[name])
    fix_md("metadata", raw)
    fix_md("versionMetadata", raw)
    if '_type' in raw:
        _type = raw['_type']
        if _type.startswith("dataset"):
            return Dataset(**raw)
        elif _type.startswith("folder"):
            return Folder(**raw)
        elif _type.startswith("group"):
            return Group(**raw)
        elif _type.startswith("location"):
            return DatasetLocation(**raw)
    # Check for metadata k:v pair
    if 'type' in raw and raw["type"] in ("integer","decimal","string"):
        value_mapping = {"integer":long, "decimal":float, "string":unicode}
        if raw["type"] in value_mapping:
            fn = value_mapping[raw["type"]]
            return raw["key"], fn(raw["value"])
        raise TypeError("No Mapping for type %s" %raw["type"])
    raise TypeError("No Default Type Information")
