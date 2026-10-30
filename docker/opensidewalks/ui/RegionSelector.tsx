import React from "react";

import Button from "react-md/src/js/Buttons";
import { DialogContainer } from "react-md/src/js/Dialogs";
import List, { ListItem } from "react-md/src/js/Lists";

import regions from "constants/regions";

import { useAppSelector, useAppDispatch } from "hooks";

import {
  endSelecting,
  set as setRegion,
  startSelecting,
} from "features/region/region-slice";

const RegionSelector = () => {
  const dispatch = useAppDispatch();
  const { selecting, name } = useAppSelector((state) => state.region);

  const sortedRegions = [...regions.features];
  sortedRegions.sort((a, b) => {
    return a.properties.name > b.properties.name ? 1 : -1;
  });

  // Nothing else in the app opens this dialog, and a dataset far from the others can not be panned to
  return (
    <>
      {regions.features.length > 1 && (
        <Button
          flat
          primary
          style={{ position: "fixed", top: 8, right: 64, zIndex: 15, background: "#fff" }}
          onClick={() => dispatch(startSelecting())}
        >
          {name}
        </Button>
      )}
      <DialogContainer
      id="region-selections-container"
      visible={selecting}
      title="Regions"
      onHide={() => dispatch(endSelecting())}
      actions={[
        {
          secondary: true,
          children: "Cancel",
          onClick: () => dispatch(endSelecting()),
        },
      ]}
    >
      <List>
        {sortedRegions.map((f) => (
          <ListItem
            key={`region-select-button-${f.properties.name}`}
            primaryText={f.properties.name}
            onClick={() => {
              dispatch(setRegion(f.properties.id));
              dispatch(endSelecting());
            }}
          />
        ))}
      </List>
    </DialogContainer>
    </>
  );
};

export default RegionSelector;
