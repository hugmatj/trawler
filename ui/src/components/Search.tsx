import { Fragment, useEffect, useState } from "react";
import { Menu, Popover, Transition } from "@headlessui/react";
import { ChevronDownIcon, SearchIcon } from "@heroicons/react/solid";
import { classNames } from "../utils";
import { gql } from "@urql/core";
import { useQuery } from "urql";
import { SearchByNameQuery, SearchQuery } from "../types";
import { EntityIcon } from "../routes/Dashboard";
import { useHistory } from "react-router";
import _ from "lodash";

export const LoadingBlock = () => (
  <div className="animate-pulse border border-gray-200 rounded-lg bg-gray-200 sm:aspect-none sm:h-16"></div>
);

export const LoadingGrid = () => (
  <div className="space-y-2">
    <LoadingBlock />
    <LoadingBlock />
  </div>
);

export const Search = () => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const history = useHistory();

  useEffect(() => {
    setOpen(search.length > 0);
  }, [search]);

  const [data] = useQuery<SearchByNameQuery>({
    query: gql`
      query SearchByName($search: String!) {
        search(filters: [{ uri: "http://schema.org/name", value: $search }]) {
          entityId
          facets {
            name
            uri
            value
          }
          type
          typeName
        }
      }
    `,
    pause: !open,
    variables: {
      search,
    },
  });

  return (
    <Popover as="div" className="relative inline-block text-left w-full">
      <div className="relative text-indigo-200 focus-within:text-gray-400">
        <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
          <SearchIcon className="h-5 w-5" aria-hidden="true" />
        </div>
        <input
          id="search"
          name="search"
          className="block w-full pl-10 pr-3 py-2 border border-transparent rounded-md leading-5 bg-indigo-400 bg-opacity-25 text-indigo-100 placeholder-indigo-200 focus:outline-none focus:bg-white focus:ring-0 focus:placeholder-gray-400 focus:text-gray-900 sm:text-sm"
          placeholder="Search projects"
          onChange={(e) => setSearch(e.target.value)}
          type="search"
        />
      </div>

      <Transition
        show={open}
        as={Fragment}
        enter="transition ease-out duration-100"
        enterFrom="transform opacity-0 scale-95"
        enterTo="transform opacity-100 scale-100"
        leave="transition ease-in duration-75"
        leaveFrom="transform opacity-100 scale-100"
        leaveTo="transform opacity-0 scale-95"
      >
        <Popover.Panel
          static
          className="origin-top-right absolute left-0 right-0 mt-2 rounded-md shadow-lg bg-white ring-1 ring-black ring-opacity-5 focus:outline-none"
        >
          <div className="p-4">
            {data.fetching ? (
              <LoadingGrid />
            ) : (
              <div>
                {_.take(data?.data?.search, 10).map((result) => (
                  <div
                    className="flex items-center py-2 px-1 cursor-pointer hover:bg-gray-200"
                    onClick={() => {
                      setSearch("");
                      history.push(`/entity/${result.entityId}`);
                    }}
                  >
                    <EntityIcon type={result.typeName} />
                    <span className="ml-2">
                      {
                        result.facets.find(
                          (facet: any) => facet.uri === "http://schema.org/name"
                        )?.value
                      }
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </Popover.Panel>
      </Transition>
    </Popover>
  );
};
